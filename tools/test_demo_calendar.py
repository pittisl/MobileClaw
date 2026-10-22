"""Device-free provider tests: python3 -m unittest discover -s tools -p test_demo_calendar.py.

FakePhone replaces only adb/provider IO. The production merge, ownership checks,
fixture recognition, snapshot verification and scenario refresh run unchanged.
"""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime
import json
from pathlib import Path
import sqlite3
from subprocess import CompletedProcess
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

from demo_calendar_common import (
    ACCOUNT, CALENDAR_COLUMNS, DISPLAY_NAME, EVENT_COLUMNS, LEGACY,
    Phone, SCENARIO_KEYS, fixture_id, general_fixture, parse_rows,
)
from demo_hotel_calendar import fixture as hotel_fixture
from demo_schedule_calendar import fixture as schedule_fixture


CHILD_COLUMNS = {
    "reminders": ["_id", "event_id", "minutes", "method"],
    "attendees": ["_id", "event_id", "attendeeName", "attendeeEmail",
                  "attendeeRelationship", "attendeeType", "attendeeStatus"],
    "extendedproperties": ["_id", "event_id", "name", "value"],
}


class FakePhone(Phone):
    """An in-memory provider with real foreign-key cascades and no shell access."""

    def __init__(self):
        super().__init__("must-never-run-adb", "fake-phone-no-device")
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.writes = []
        self.after_write = None
        self._create("calendars", CALENDAR_COLUMNS)
        self._create("events", EVENT_COLUMNS, "calendar_id", "calendars")
        for table, columns in CHILD_COLUMNS.items():
            self._create(table, columns, "event_id", "events")

    def _create(self, table, columns, reference=None, parent=None):
        declarations = []
        for column in columns:
            if column == "_id":
                definition = "INTEGER PRIMARY KEY AUTOINCREMENT"
            elif column == reference:
                definition = f"INTEGER REFERENCES {parent}(_id) ON DELETE CASCADE"
            elif column in {"dtstart", "dtend"}:
                definition = "INTEGER"
            elif column in {"allDay", "deleted"}:
                definition = "INTEGER DEFAULT 0"
            else:
                definition = "TEXT"
            declarations.append(f'"{column}" {definition}')
        self.db.execute(f"CREATE TABLE {table} ({', '.join(declarations)})")

    def shell(self, *args):
        raise AssertionError("A device-free test attempted to use adb")

    def close(self):
        self.db.close()

    def add(self, table, **values):
        keys = list(values)
        self.db.execute(f"INSERT INTO {table} ({','.join(keys)}) VALUES ({','.join('?' for _ in keys)})",
                        [values[key] for key in keys])
        return self.db.execute("SELECT last_insert_rowid()").fetchone()[0]

    def add_calendar(self, calendar_id, account, account_type="LOCAL", **overrides):
        return self.add("calendars", **{
            "_id": calendar_id, "account_name": account, "account_type": account_type,
            "name": account, "ownerAccount": account,
            "calendar_displayName": DISPLAY_NAME if account == ACCOUNT else account,
            **overrides,
        })

    def add_event(self, event_id, calendar_id, **values):
        return self.add("events", **{
            "_id": event_id, "calendar_id": calendar_id, "title": "Test event",
            "dtstart": 1_792_180_800_000, "dtend": 1_792_184_400_000,
            "eventTimezone": "America/New_York", **values,
        })

    def dump(self):
        return {table: [dict(row) for row in self.db.execute(f"SELECT * FROM {table} ORDER BY _id")]
                for table in ["calendars", "events", *CHILD_COLUMNS]}

    @staticmethod
    def _table(uri):
        table = urlsplit(uri).path.lstrip("/")
        if table not in {"calendars", "events", *CHILD_COLUMNS}:
            raise AssertionError(f"Unexpected provider URI: {uri}")
        return table

    def query(self, uri, projection, where):
        table = self._table(uri)
        rows = self.db.execute(f"SELECT {','.join(projection)} FROM {table} WHERE {where}")
        return [{key: "NULL" if row[key] is None else str(row[key]) for key in projection}
                for row in rows]

    def content(self, *args):
        action, *options = args
        parameters, values = {}, {}
        for i in range(0, len(options), 2):
            option, value = options[i:i + 2]
            if option == "--bind":
                # Android content uses colon as an unescaped separator. A value
                # containing another colon is rejected even with shell quoting.
                fields = value.split(":")
                if len(fields) != 3:
                    raise RuntimeError("[ERROR] Binding not well formed")
                column, kind, bound = fields
                values[column] = int(bound) if kind in {"i", "l"} else bound
            else:
                parameters[option] = value
        table = self._table(parameters["--uri"])
        where = parameters.get("--where", "1")
        if action == "insert":
            # Calendar color/access fields are outside the identity projection.
            if table == "calendars":
                values = {key: value for key, value in values.items() if key in CALENDAR_COLUMNS}
            changed = [self.add(table, **values)]
        else:
            changed = [row[0] for row in self.db.execute(f"SELECT _id FROM {table} WHERE {where}")]
            if action == "update":
                assignments = ",".join(f"{key}=?" for key in values)
                self.db.execute(f"UPDATE {table} SET {assignments} WHERE {where}", list(values.values()))
            elif action == "delete":
                self.db.execute(f"DELETE FROM {table} WHERE {where}")
            else:
                raise AssertionError(f"Unexpected provider operation: {action}")
        self.writes.append((action, table, tuple(changed), deepcopy(values)))
        if self.after_write:
            self.after_write(action, table, changed, values)
        return f"{action}: {len(changed)} rows"


class CommandBoundaryTest(unittest.TestCase):
    def test_fake_provider_rejects_colon_inside_a_bound_value(self):
        phone = FakePhone()
        self.addCleanup(phone.close)
        phone.add_calendar(3, ACCOUNT)
        phone.add_event(201, 3)
        before = phone.dump()
        with self.assertRaisesRegex(RuntimeError, "Binding not well formed"):
            phone.content("update", "--uri", phone.sync_uri("content://com.android.calendar/events"),
                          "--where", "_id=201", "--bind", "_sync_id:s:mobileclaw-demo:hotel:arrival")
        self.assertEqual(before, phone.dump())
        self.assertEqual([], phone.writes)

    def test_fixture_markers_fit_android_content_bind_syntax(self):
        for scenario, keys in SCENARIO_KEYS.items():
            for key in keys:
                with self.subTest(scenario=scenario, key=key):
                    self.assertNotIn(":", fixture_id(scenario, key))

    def test_zero_exit_with_error_diagnostic_is_still_failure(self):
        for stdout, stderr in [
            ("[ERROR] Binding not well formed: _sync_id:s:old:marker\n", ""),
            ("", "[ERROR] Binding not well formed\n"),
            ("[error] Binding not well formed\n", ""),
        ]:
            with self.subTest(stdout=stdout, stderr=stderr):
                completed = CompletedProcess(["adb"], 0, stdout=stdout, stderr=stderr)
                with patch("demo_calendar_common.subprocess.run", return_value=completed) as run:
                    with self.assertRaisesRegex(RuntimeError, "Binding not well formed"):
                        Phone("must-never-run-adb", "fake-phone-no-device").shell("content", "update")
                run.assert_called_once()

    def test_successful_shell_output_is_preserved_with_crlf_normalized(self):
        completed = CompletedProcess(["adb"], 0, stdout="Updated 1 row.\r\n", stderr="")
        with patch("demo_calendar_common.subprocess.run", return_value=completed):
            result = Phone("must-never-run-adb", "fake-phone-no-device").shell("content", "update")
        self.assertEqual("Updated 1 row.\n", result)


class ProviderRowsTest(unittest.TestCase):
    def test_description_newlines_commas_and_equals_are_content(self):
        output = ("Row: 0 _id=11, title=Hotel, description=First line, with commas\n"
                  "second line has x=y and https://example.test/a?b=c, dtstart=123\n"
                  "Row: 1 _id=12, title=Meeting, description=NULL, dtstart=456\n")
        rows = parse_rows(output, ["_id", "title", "description", "dtstart"])
        self.assertEqual(2, len(rows))
        self.assertEqual("First line, with commas\nsecond line has x=y and https://example.test/a?b=c",
                         rows[0]["description"])
        self.assertEqual("456", rows[1]["dtstart"])

    def test_ambiguous_field_text_is_rejected(self):
        for description in ["memo, title=forged", "memo, unknown_field=forged"]:
            with self.subTest(description=description), self.assertRaises(RuntimeError):
                parse_rows(f"Row: 0 _id=11, title=Original, description={description}\n",
                           ["_id", "title", "description"])

    def test_embedded_row_header_cannot_silently_drop_description(self):
        with self.assertRaises(RuntimeError):
            parse_rows("Row: 0 _id=11, description=Memo\nRow: 9 forged text\n",
                       ["_id", "description"])

    def test_missing_columns_and_nonprovider_output_are_rejected(self):
        for output in ["Row: 0 _id=11\n", "Permission denied\n"]:
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                parse_rows(output, ["_id", "title"])

    def test_empty_provider_result(self):
        self.assertEqual([], parse_rows("No result found.\n", ["_id"]))
        self.assertEqual([], parse_rows("\n", ["_id"]))


class ProviderTestCase(unittest.TestCase):
    def setUp(self):
        self.phone = FakePhone()
        self.addCleanup(self.phone.close)
        self.temp = tempfile.TemporaryDirectory(prefix="mobileclaw-calendar-test-")
        self.addCleanup(self.temp.cleanup)
        self.backups = Path(self.temp.name)

    def assert_no_calendar_deletes(self):
        self.assertFalse(any(action == "delete" and table == "calendars"
                             for action, table, _, _ in self.phone.writes))

    def populate_legacy(self):
        accounts = [ACCOUNT, *LEGACY]
        for calendar_id, account in zip([3, 4, 5, 8], accounts):
            self.phone.add_calendar(calendar_id, account)
        self.phone.add_calendar(99, "personal@example.test", account_type="com.google")
        general = general_fixture("America/New_York",
                                  datetime(2026, 10, 16, 12, tzinfo=ZoneInfo("America/New_York")))
        for event_id, values in enumerate(general, 101):
            # Android reports the old deadline's missing location as NULL.
            values = {**values, "eventLocation": values["eventLocation"] or None}
            self.phone.add_event(event_id, 3, **values)
        for event_id, values in enumerate(hotel_fixture(), 201):
            self.phone.add_event(event_id, 4, **values)
        for event_id, values in enumerate(schedule_fixture(), 301):
            self.phone.add_event(event_id, 5, **values)
        self.phone.add_event(401, 8, title="MobileClaw demo meeting", eventLocation="Fictional demo room")
        self.phone.add_event(402, 8, title="MobileClaw demo review", eventLocation="Fictional demo room")
        self.phone.add_event(350, 5, title="Test event", description="User memo, keep exactly.\nSecond line: x=y")
        self.phone.add_event(351, 5, title="Recurring exception", original_id="350", original_sync_id="user-series",
                             originalInstanceTime="1792180800000", rrule="FREQ=WEEKLY;COUNT=2")
        self.phone.add_event(990, 99, description="Personal Google event")
        self.phone.add("reminders", _id=1, event_id=201, minutes="15", method="1")
        self.phone.add("attendees", _id=2, event_id=350, attendeeName="Fictional guest",
                       attendeeEmail="guest@example.test", attendeeStatus="1")
        self.phone.add("extendedproperties", _id=3, event_id=351, name="local-note", value="keep, this\nverbatim")


class MergeTest(ProviderTestCase):
    def test_four_calendars_merge_in_place_and_preserve_related_rows(self):
        self.populate_legacy()
        before = self.phone.dump()
        result = self.phone.merge(self.backups)
        after = self.phone.dump()
        self.assertEqual(3, result["calendar_id"])
        self.assertEqual([4, 5, 8], result["removed_empty_calendar_ids"])
        self.assertEqual({3, 99}, {row["_id"] for row in after["calendars"]})
        self.assertEqual([row["_id"] for row in before["events"]], [row["_id"] for row in after["events"]])
        for old, new in zip(before["events"], after["events"]):
            with self.subTest(event_id=old["_id"]):
                self.assertEqual({key: value for key, value in old.items() if key not in {"calendar_id", "_sync_id"}},
                                 {key: value for key, value in new.items() if key not in {"calendar_id", "_sync_id"}})
                self.assertEqual(99 if old["_id"] == 990 else 3, new["calendar_id"])
        for table in CHILD_COLUMNS:
            self.assertEqual(before[table], after[table])
        events = {row["_id"]: row for row in after["events"]}
        self.assertIsNone(events[350]["_sync_id"])
        self.assertIsNone(events[351]["_sync_id"])
        self.assertEqual("350", events[351]["original_id"])
        self.assertEqual(fixture_id("hotel", "arrival"), events[201]["_sync_id"])
        backup = json.loads(Path(result["backup"]).read_text())
        self.assertEqual([3, 4, 5, 8], [int(row["_id"]) for row in backup["calendars"]])
        self.assertEqual(result["event_count"], len(backup["events"]))
        self.assertEqual("User memo, keep exactly.\nSecond line: x=y",
                         next(row for row in backup["events"] if row["_id"] == 350)["description"])
        self.assertEqual(backup["children"], result["children"])

    def test_repeat_merge_has_no_duplicate_rows_or_new_fixture_ids(self):
        self.populate_legacy()
        self.phone.merge(self.backups)
        after_first = self.phone.dump()
        self.phone.writes.clear()
        result = self.phone.merge(self.backups)
        self.assertEqual(after_first, self.phone.dump())
        self.assertEqual([], self.phone.writes)
        self.assertEqual([], result["removed_empty_calendar_ids"])
        self.assertEqual(2, len(list(self.backups.glob("*.json"))))

    def test_missing_canonical_is_created_without_recreating_events(self):
        self.phone.add_calendar(4, "MobileClawHotelDemo")
        self.phone.add_event(201, 4, **hotel_fixture()[0])
        result = self.phone.merge(self.backups)
        self.assertNotEqual(4, result["calendar_id"])
        self.assertEqual([201], [event["_id"] for event in result["events"]])
        self.assertEqual(result["calendar_id"], result["events"][0]["calendar_id"])

    def test_bad_owner_or_duplicate_account_refuses_all_writes(self):
        for broken in ["owner", "duplicate"]:
            with self.subTest(broken=broken):
                phone = FakePhone()
                self.addCleanup(phone.close)
                phone.add_calendar(3, ACCOUNT)
                phone.add_calendar(4, "MobileClawHotelDemo",
                                   ownerAccount="unexpected-owner" if broken == "owner" else "MobileClawHotelDemo")
                if broken == "duplicate":
                    phone.add_calendar(6, "MobileClawHotelDemo")
                before = phone.dump()
                with self.assertRaisesRegex(RuntimeError, "metadata"):
                    phone.merge(self.backups)
                self.assertEqual([], phone.writes)
                self.assertEqual(before, phone.dump())

    def test_payload_mutation_after_a_move_prevents_calendar_deletion(self):
        self.populate_legacy()

        def corrupt(action, table, changed, values):
            if action == "update" and table == "events" and "calendar_id" in values:
                self.phone.db.execute("UPDATE events SET description='unexpected replacement' WHERE _id=?", [changed[0]])

        self.phone.after_write = corrupt
        with self.assertRaisesRegex(RuntimeError, "payload changed"):
            self.phone.merge(self.backups)
        self.assert_no_calendar_deletes()
        self.assertEqual(1, len(list(self.backups.glob("*.json"))))

    def test_changed_reminder_reference_prevents_calendar_deletion(self):
        self.populate_legacy()

        def corrupt(action, table, changed, values):
            if action == "update" and table == "events" and "calendar_id" in values:
                self.phone.db.execute("UPDATE reminders SET event_id=350 WHERE _id=1")

        self.phone.after_write = corrupt
        with self.assertRaisesRegex(RuntimeError, "reminders/attendees changed"):
            self.phone.merge(self.backups)
        self.assert_no_calendar_deletes()

    def test_silently_ignored_marker_write_prevents_calendar_deletion(self):
        self.populate_legacy()

        def ignore_marker(action, table, changed, values):
            if action == "update" and table == "events" and "_sync_id" in values and 201 in changed:
                # Some command failures report a successful exit. The read-back
                # must catch a write that never reached the real provider row.
                self.phone.db.execute("UPDATE events SET _sync_id=NULL WHERE _id=201")

        self.phone.after_write = ignore_marker
        with self.assertRaises(RuntimeError):
            self.phone.merge(self.backups)
        self.assert_no_calendar_deletes()
        self.assertEqual({3, 4, 5, 8, 99}, {row["_id"] for row in self.phone.dump()["calendars"]})
        self.assertIsNone(next(row for row in self.phone.dump()["events"] if row["_id"] == 201)["_sync_id"])

    def test_duplicate_newly_recognized_fixture_prevents_calendar_deletion(self):
        self.phone.add_calendar(3, ACCOUNT)
        self.phone.add_calendar(4, "MobileClawHotelDemo")
        self.phone.add_event(201, 3, **hotel_fixture()[0], _sync_id=fixture_id("hotel", "arrival"))
        self.phone.add_event(202, 4, **hotel_fixture()[0])
        with self.assertRaisesRegex(RuntimeError, "Duplicate fixture"):
            self.phone.merge(self.backups)
        self.assert_no_calendar_deletes()

    def test_duplicate_existing_sync_ids_prevent_calendar_deletion(self):
        self.phone.add_calendar(3, ACCOUNT)
        self.phone.add_calendar(4, "MobileClawHotelDemo")
        for event_id, calendar_id in [(201, 3), (202, 4)]:
            self.phone.add_event(event_id, calendar_id, **hotel_fixture()[0],
                                 _sync_id=fixture_id("hotel", "arrival"))
        with self.assertRaisesRegex(RuntimeError, "[Dd]uplicate"):
            self.phone.merge(self.backups)
        self.assert_no_calendar_deletes()


class ScenarioRefreshTest(ProviderTestCase):
    def test_hotel_and_schedule_upserts_preserve_other_scenarios_and_user_events(self):
        for scenario, fixture in [("hotel", hotel_fixture), ("schedule", schedule_fixture)]:
            with self.subTest(scenario=scenario):
                phone = FakePhone()
                self.addCleanup(phone.close)
                phone.add_calendar(3, ACCOUNT)
                other = "schedule" if scenario == "hotel" else "hotel"
                other_fixture = schedule_fixture if other == "schedule" else hotel_fixture
                phone.seed_scenario(other, other_fixture())
                phone.add_event(900, 3, **fixture()[0], description="Same title, but owned by user")
                phone.add_event(901, 3, title="Test event", description="Keep, this.\nExact text")
                protected = deepcopy(phone.dump())
                phone.seed_scenario(scenario, fixture())
                first = phone.scenario_events(3, scenario)
                ids = {event["_sync_id"]: event["_id"] for event in first}
                self.assertEqual(len(SCENARIO_KEYS[scenario]), len(first))
                revised = deepcopy(fixture())
                revised[0]["dtend"] += 60_000
                phone.seed_scenario(scenario, revised)
                refreshed = phone.scenario_events(3, scenario)
                self.assertEqual(ids, {event["_sync_id"]: event["_id"] for event in refreshed})
                self.assertEqual(revised[0]["dtend"], refreshed[0]["dtend"])
                by_id = {row["_id"]: row for row in phone.dump()["events"]}
                for event in protected["events"]:
                    self.assertEqual(event, by_id[event["_id"]])
                self.assertIsNone(by_id[900]["_sync_id"])
                self.assertIsNone(by_id[901]["_sync_id"])

    def test_refresh_keeps_existing_fixture_description_and_reminder_id(self):
        self.phone.add_calendar(3, ACCOUNT)
        self.phone.add_event(201, 3, **hotel_fixture()[0], _sync_id=fixture_id("hotel", "arrival"),
                             description="Retain my extra fixture note")
        self.phone.add("reminders", _id=1, event_id=201, minutes="10", method="1")
        self.phone.seed_scenario("hotel", hotel_fixture())
        event = next(event for event in self.phone.read_events(3) if event["_id"] == 201)
        self.assertEqual("Retain my extra fixture note", event["description"])
        self.assertEqual(201, self.phone.dump()["reminders"][0]["event_id"])

    def test_duplicate_scenario_sync_ids_refuse_refresh_before_writes(self):
        self.phone.add_calendar(3, ACCOUNT)
        for event_id in [201, 202]:
            self.phone.add_event(event_id, 3, **hotel_fixture()[0], _sync_id=fixture_id("hotel", "arrival"))
        before = self.phone.dump()
        with self.assertRaisesRegex(RuntimeError, "Duplicate scenario"):
            self.phone.seed_scenario("hotel", hotel_fixture())
        self.assertEqual([], self.phone.writes)
        self.assertEqual(before, self.phone.dump())

    def test_seeding_legacy_phone_requires_explicit_merge(self):
        self.phone.add_calendar(4, "MobileClawHotelDemo")
        self.phone.add_event(201, 4, **hotel_fixture()[0])
        before = self.phone.dump()
        with self.assertRaisesRegex(RuntimeError, "--merge first"):
            self.phone.seed_scenario("hotel", hotel_fixture())
        self.assertEqual([], self.phone.writes)
        self.assertEqual(before, self.phone.dump())


if __name__ == "__main__":
    unittest.main()
