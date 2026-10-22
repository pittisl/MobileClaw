"""Turn MobileClaw's reasoning trace (logcat tag MobileClaw-TRACE) back into events.

The app prints each trace event as a block of log lines (ReasoningTrace.emit): a rule
header for a triage or a chat turn, "▸ LABEL" section lines, four-space-indented bodies,
and "    ↳ " continuations where a long line was hard-wrapped. Blocks from different
threads interleave in the log — a triage lands in the middle of a follow-up turn — so
lines are grouped per thread, and tool calls, results and answers are attached to their
turn by the "#n" tag. Untagged lines go to the most recent turn still open;
overlapping turns cannot be distinguished without tags.

Everything here is already redacted by the app: this module only re-arranges text.
Pure stdlib, so `python3 -m unittest` runs it on any laptop.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

# 10-01 14:55:57.251 12848 12900 I MobileClaw-TRACE: text   (logcat -v threadtime)
_THREADTIME = re.compile(r"^(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+) [VDIWEF] MobileClaw-TRACE\s*: ?(.*)$")
# 10-01 14:55:57.251 I/MobileClaw-TRACE(12848): text        (logcat -v time)
_TIME = re.compile(r"^(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d+) [VDIWEF]/MobileClaw-TRACE\(\s*(\d+)\): ?(.*)$")

_RULE_HEAD = re.compile(r"^════ (.+?) ─ (\d\d:\d\d:\d\d) ─ (.*?) ═+\s*$")
_RULE_END = re.compile(r"^═{20,}\s*$")
_SECTION = re.compile(r"^▸ ([A-Z][A-Z ]*?)(?:  (.*))?$")
_TAG = re.compile(r"^#(\d+)\s+(.*)$")
_TURN_KIND = re.compile(r"^CHAT TURN(?: #(\d+))?$")
ACTIONS_SENTINEL = "@@ACTIONS@@"


@dataclass
class Section:
    label: str
    head: str
    body: list[str] = field(default_factory=list)

    def text(self) -> str:
        return "\n".join(self.body)


@dataclass
class Triage:
    id: str
    time: str
    app: str
    sections: list[Section] = field(default_factory=list)

    def to_json(self) -> dict:
        sec = {s.label: s for s in self.sections}
        decision = sec.get("DECISION")
        notif = sec.get("NOTIFICATION")
        return {
            "type": "triage", "id": self.id, "time": self.time, "app": self.app,
            "title": notif.head if notif else "",
            "body": notif.text() if notif else "",
            "decision": decision.head if decision else "",
            "reasoning": decision.text() if decision else "",
        }


@dataclass
class Step:
    tool: str
    args: str
    result: str | None = None


@dataclass
class Turn:
    id: str
    n: int | None
    time: str
    title: str
    sections: list[Section] = field(default_factory=list)
    steps: list[Step] = field(default_factory=list)
    answer: Section | None = None

    def to_json(self) -> dict:
        sec = {s.label: s for s in self.sections}
        prompt = sec.get("SYSTEM PROMPT")
        history = sec.get("HISTORY")
        out = {
            "type": "turn", "id": self.id, "n": self.n, "time": self.time, "title": self.title,
            "prompt_head": prompt.head if prompt else "",
            "prompt": prompt.text() if prompt else "",
            "history_head": history.head if history else "",
            "history": history.text() if history else "",
            "steps": [{"tool": s.tool, "args": s.args, "result": s.result} for s in self.steps],
            "done": self.answer is not None,
        }
        if self.answer is not None:
            text, actions = split_actions(self.answer.text())
            out.update(answer_head=self.answer.head, answer=text, actions=actions)
        return out


def split_actions(text: str) -> tuple[str, list[str]]:
    """The answer's prose and its "@@ACTIONS@@ A | B" chips, as the app's StreamText.split does."""
    i = text.find(ACTIONS_SENTINEL)
    if i < 0:
        return text.strip(), []
    chips = [c.strip() for c in text[i + len(ACTIONS_SENTINEL):].split("|") if c.strip()]
    return text[:i].strip(), chips


class TraceParser:
    """Feed it log lines; it returns the events each line changed, as JSON-ready dicts."""

    def __init__(self) -> None:
        self.events: dict[str, Triage | Turn] = {}
        self.order: list[str] = []
        self._turns_by_n: dict[int, Turn] = {}
        self._open_turns: list[Turn] = []   # untagged-log fallback, most recent last
        self._thread_block: dict[str, Triage | Turn | None] = {}
        self._thread_section: dict[str, tuple[object, Section] | None] = {}
        self._seq = 0

    # -- input -------------------------------------------------------------------------

    @staticmethod
    def split(line: str) -> tuple[str, str] | None:
        """(thread key, message) for a trace line in either logcat format, else None."""
        line = line.rstrip("\r\n")
        m = _THREADTIME.match(line)
        if m:
            return f"{m.group(3)}/{m.group(4)}", m.group(5)
        m = _TIME.match(line)
        if m:
            return m.group(3), m.group(4)
        return None

    def feed(self, line: str) -> list[dict]:
        parsed = self.split(line)
        if parsed is None:
            return []
        thread, msg = parsed
        changed = self._message(thread, msg)
        return [changed.to_json()] if changed is not None else []

    # -- the state machine ---------------------------------------------------------------

    def _new_id(self, prefix: str) -> str:
        self._seq += 1
        return f"{prefix}{self._seq}"

    def _add(self, ev: Triage | Turn) -> None:
        self.events[ev.id] = ev
        self.order.append(ev.id)

    def _message(self, thread: str, msg: str):
        # A continuation of a hard-wrapped line: glue it to whatever this thread wrote last.
        if msg.startswith("    ↳ "):
            current = self._thread_section.get(thread)
            if current is None:
                return None
            owner, section = current
            piece = msg[len("    ↳ "):]
            if section.body:
                section.body[-1] += piece
            else:
                section.head += piece
            self._refresh_step(owner, section)
            return owner

        if msg.startswith("    ") or msg == "":
            current = self._thread_section.get(thread)
            if current is None:
                return None
            owner, section = current
            section.body.append(msg[4:])
            self._refresh_step(owner, section)
            return owner

        head = _RULE_HEAD.match(msg)
        if head:
            kind, clock, subject = head.groups()
            self._thread_section[thread] = None
            turn_kind = _TURN_KIND.match(kind)
            if kind == "TRIAGE":
                ev = Triage(self._new_id("t"), clock, subject)
                self._add(ev)
                self._thread_block[thread] = ev
                return ev
            if turn_kind:
                n = int(turn_kind.group(1)) if turn_kind.group(1) else None
                ev = Turn(self._new_id("c"), n, clock, subject)
                self._add(ev)
                if n is not None:
                    self._turns_by_n[n] = ev
                self._open_turns.append(ev)
                self._thread_block[thread] = ev
                return ev
            # REPLAY and anything newer: not shown, but its lines must not leak into a turn.
            self._thread_block[thread] = None
            return None

        if _RULE_END.match(msg):
            self._thread_block[thread] = None
            self._thread_section[thread] = None
            return None

        sec = _SECTION.match(msg)
        if not sec:
            return None
        label, rest = sec.group(1), sec.group(2) or ""
        if label in ("TOOL CALL", "TOOL RESULT", "ANSWER"):
            turn, rest = self._turn_for(rest)
            if turn is None:
                return None
            if label == "TOOL CALL":
                name, _, args = rest.partition("  ")
                turn.steps.append(Step(name.strip(), args.strip()))
                section = Section(label, rest)
            elif label == "TOOL RESULT":
                section = Section(label, rest.strip())
                step = next((s for s in reversed(turn.steps) if s.result is None and s.tool == rest.strip()), None)
                if step is not None:
                    step.result = ""
            else:
                section = Section(label, rest)
                turn.answer = section
                if turn in self._open_turns:
                    self._open_turns.remove(turn)
            self._thread_section[thread] = (turn, section)
            return turn

        block = self._thread_block.get(thread)
        if block is None:
            return None
        section = Section(label, rest)
        block.sections.append(section)
        self._thread_section[thread] = (block, section)
        return block

    def _turn_for(self, rest: str) -> tuple[Turn | None, str]:
        tag = _TAG.match(rest)
        if tag:
            return self._turns_by_n.get(int(tag.group(1))), tag.group(2)
        return (self._open_turns[-1] if self._open_turns else None), rest

    @staticmethod
    def _refresh_step(owner, section: Section) -> None:
        # A tool result's body is the step's result; keep the step current as lines arrive.
        if isinstance(owner, Turn) and section.label == "TOOL RESULT":
            step = next((s for s in reversed(owner.steps) if s.tool == section.head and s.result is not None), None)
            if step is not None:
                step.result = section.text()

    # -- output --------------------------------------------------------------------------

    def snapshot(self) -> list[dict]:
        return [self.events[i].to_json() for i in self.order]


class Resume:
    """Which lines of a logcat stream are new, across reconnects (the viewer's replug case).

    The first connection takes every trace line. A reconnect re-reads from the last line's
    timestamp (`logcat -T`, inclusive), so it skips up to and including that exact line —
    or, if the buffer has rotated it out, everything not later than its timestamp. Never by
    timestamp alone on a live stream: one event's lines share a millisecond, and a
    "not later than the last one" rule drops every body line after its header (it did).
    """

    def __init__(self) -> None:
        self.last_stamp: str | None = None
        self.last_line: str | None = None
        self._resuming = False

    def reconnect(self) -> str | None:
        """Call before re-reading; returns the `-T` argument, or None on the first read."""
        self._resuming = self.last_line is not None
        return self.last_stamp

    def admit(self, line: str) -> bool:
        if TraceParser.split(line) is None:
            return False
        stamp = line[:18]
        if self._resuming:
            if line == self.last_line:
                self._resuming = False
                return False
            if self.last_stamp is not None and stamp <= self.last_stamp:
                return False
            self._resuming = False
        self.last_stamp, self.last_line = stamp, line
        return True

