"""Run with: python3 -m unittest discover -s tools/trace_viewer"""

import unittest
from pathlib import Path

from trace_parse import Resume, TraceParser, split_actions

DATA = Path(__file__).resolve().parent / "testdata"


def parse(name: str) -> list[dict]:
    p = TraceParser()
    for line in (DATA / name).read_text(encoding="utf-8").splitlines():
        p.feed(line)
    return p.snapshot()


class TaggedInterleavedTurns(unittest.TestCase):
    """Two turns in flight at once plus a triage between them, in `-v threadtime` form."""

    def setUp(self):
        self.events = parse("interleaved-tagged.log")
        self.turns = {e["n"]: e for e in self.events if e["type"] == "turn"}

    def test_each_tool_call_lands_in_the_turn_its_tag_names(self):
        self.assertEqual([s["tool"] for s in self.turns[3]["steps"]], ["read_calendar"])
        self.assertEqual([s["tool"] for s in self.turns[4]["steps"]], ["search_files"])
        self.assertIn("Flight to Austin", self.turns[3]["steps"][0]["result"])
        self.assertIn("UA1234 Departs 07:15", self.turns[4]["steps"][0]["result"])

    def test_answers_and_chips_go_to_their_own_turn(self):
        self.assertTrue(self.turns[3]["done"])
        self.assertEqual(self.turns[3]["actions"], ["Reply 7:15 AM", "Ask about a ride"])
        self.assertNotIn("@@ACTIONS@@", self.turns[3]["answer"])
        self.assertEqual(self.turns[4]["answer"], "Found your boarding pass.")

    def test_a_triage_between_them_stays_whole_and_out_of_both(self):
        triage = [e for e in self.events if e["type"] == "triage"]
        self.assertEqual(len(triage), 1)
        self.assertEqual(triage[0]["decision"], "reply · high · confidence 0.91")
        self.assertEqual(triage[0]["body"], "still on for dinner?")
        self.assertNotIn("dinner", self.turns[3]["prompt"] + self.turns[4]["prompt"])

    def test_a_wrapped_line_is_glued_back(self):
        self.assertIn("wrapped it at the per-entry limit, and the viewer", self.turns[3]["prompt"])

    def test_order_is_arrival_order(self):
        self.assertEqual([e["type"] for e in self.events], ["turn", "turn", "triage"])


class SyntheticUntaggedTurns(unittest.TestCase):
    """Handwritten `-v time` input: four triages and two sequential follow-ups."""

    def setUp(self):
        self.events = parse("hotel-followup-untagged.log")

    def test_four_triages_and_two_turns(self):
        self.assertEqual(sum(e["type"] == "triage" for e in self.events), 4)
        self.assertEqual(sum(e["type"] == "turn" for e in self.events), 2)

    def test_steps_follow_the_open_turn(self):
        first, second = [e for e in self.events if e["type"] == "turn"]
        self.assertEqual([s["tool"] for s in first["steps"]], ["read_calendar"])
        self.assertEqual([s["tool"] for s in second["steps"]],
                         ["read_calendar", "recall", "search_files", "search_files", "search_files"])
        self.assertEqual(first["steps"][0]["result"], "Demo arrival: 14:00 at Maple Inn.")
        self.assertEqual([step["result"] for step in second["steps"]], [
            "Demo check-in: 15:00 at Maple Inn.",
            "No saved demo memories.",
            "demo-booking.txt: Maple Inn, booking DEMO42.",
            "No matching demo files.",
            "demo-services.txt: luggage storage is available.",
        ])

    def test_the_suggestion_and_its_chips(self):
        first, second = [e for e in self.events if e["type"] == "turn"]
        self.assertTrue(first["done"] and second["done"])
        self.assertEqual(first["actions"], ["Find the booking", "Review arrival"])
        self.assertEqual(second["actions"], [
            "Draft a reply with Maple Inn and DEMO42", "Review booking",
        ])
        self.assertEqual(second["answer"], "The demo booking is Maple Inn, code DEMO42.")
        self.assertIn("200 in / 40 out", second["answer_head"])


class Pieces(unittest.TestCase):
    def test_split_actions(self):
        self.assertEqual(split_actions("Hi\n@@ACTIONS@@ A | B |"), ("Hi", ["A", "B"]))
        self.assertEqual(split_actions("no chips"), ("no chips", []))

    def test_lines_that_are_not_trace_are_ignored(self):
        p = TraceParser()
        self.assertEqual(p.feed("--------- beginning of main"), [])
        self.assertEqual(p.feed("10-01 15:00:00.000  1  2 I MobileClaw-AI: tool_call: x"), [])
        self.assertEqual(p.snapshot(), [])


class ResumeAcrossReconnects(unittest.TestCase):
    L = [
        "10-01 15:03:11.391 13301 13356 I MobileClaw-TRACE: ▸ TOOL RESULT  #1  read_calendar",
        "10-01 15:03:11.391 13301 13356 I MobileClaw-TRACE:     Events:",
        "10-01 15:03:11.391 13301 13356 I MobileClaw-TRACE:     Sat 3 Oct, 07:15 — Flight to Austin",
        "10-01 15:03:11.393 13301 13356 I MobileClaw-TRACE: ▸ TOOL CALL  #1  recall  {}",
    ]

    def test_lines_sharing_a_millisecond_all_get_through(self):
        r = Resume()
        self.assertIsNone(r.reconnect())
        self.assertEqual([r.admit(x) for x in self.L], [True, True, True, True])

    def test_a_reconnect_skips_what_was_shown_and_nothing_after(self):
        r = Resume()
        r.reconnect()
        for x in self.L[:2]:
            r.admit(x)
        self.assertEqual(r.reconnect(), "10-01 15:03:11.391")
        # logcat -T replays from that millisecond, inclusive.
        self.assertEqual([r.admit(x) for x in self.L], [False, False, True, True])

    def test_a_rotated_buffer_falls_back_to_strictly_later(self):
        r = Resume()
        r.reconnect()
        r.admit(self.L[1])
        r.reconnect()
        self.assertEqual([r.admit(x) for x in (self.L[2].replace("Austin", "AUS"), self.L[3])], [False, True])

    def test_non_trace_lines_are_not_admitted(self):
        self.assertFalse(Resume().admit("--------- beginning of main"))


if __name__ == "__main__":
    unittest.main()
