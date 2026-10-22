"""One LOCAL calendar for every MobileClaw rehearsal, with scenario-owned fixtures.

Events move in place when old scenario calendars are merged, so reminders, attendees
and event IDs survive. A hidden sync id identifies generated fixtures; a hand-added
event is never swept up by another scenario's refresh.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone as utc_timezone
import json
from pathlib import Path
import re
import shlex
import subprocess
import uuid
from zoneinfo import ZoneInfo

ACCOUNT = "MobileClawDemo"
ACCOUNT_TYPE = "LOCAL"
DISPLAY_NAME = "MobileClaw demo"
CALENDARS = "content://com.android.calendar/calendars"
EVENTS = "content://com.android.calendar/events"
PREFIX = "mobileclaw-demo."
LEGACY = {"MobileClawHotelDemo": "hotel", "MobileClawScheduleDemo": "schedule",
          "MobileClawCallbackDemo": "callback"}
CALENDAR_COLUMNS = ["_id", "account_name", "account_type", "name", "ownerAccount",
                    "calendar_displayName"]
EVENT_COLUMNS = ["_id", "calendar_id", "_sync_id", "title", "eventLocation", "description",
                 "dtstart", "dtend", "duration", "eventTimezone", "eventEndTimezone",
                 "allDay", "rrule", "rdate", "exrule", "exdate", "original_id",
                 "original_sync_id", "originalInstanceTime", "originalAllDay", "eventStatus",
                 "availability", "accessLevel", "hasAlarm", "hasAttendeeData", "organizer",
                 "guestsCanModify", "guestsCanInviteOthers", "guestsCanSeeGuests",
                 "selfAttendeeStatus", "deleted", "customAppPackage", "customAppUri", "uid2445"]
PAYLOAD_COLUMNS = [key for key in EVENT_COLUMNS if key not in {"calendar_id", "_sync_id"}]
NUMERIC = {"_id", "calendar_id", "dtstart", "dtend", "allDay", "deleted"}
SCENARIO_KEYS = {
    "general": ["meeting", "dentist", "deadline", "flight"],
    "hotel": ["arrival", "hotel-arrival"],
    "schedule": ["poster", "setup", "research"],
    "callback": ["meeting", "review"],
}


def parse_rows(output: str, projection: list[str]) -> list[dict[str, str]]:
    if not output.strip() or output.strip() == "No result found.":
        return []
    rows = []
    # Description fields can contain newlines; only a provider's Row header starts
    # a new row. Ambiguous field text fails before a destructive operation.
    for block in re.split(r"(?m)^Row: \d+ ", output.replace("\r", ""))[1:]:
        row = {}
        for field in re.split(r", (?=[A-Za-z_][A-Za-z_0-9]*=)", block.rstrip("\n")):
            key, separator, value = field.partition("=")
            if not separator or key not in projection or key in row:
                raise RuntimeError(f"Unparseable calendar provider field: {field}")
            row[key] = value
        if set(row) != set(projection):
            raise RuntimeError("Calendar provider omitted requested columns")
        rows.append(row)
    if not rows:
        raise RuntimeError(f"Unexpected calendar provider output: {output}")
    return rows


def fixture_id(scenario: str, key: str) -> str:
    return f"{PREFIX}{scenario}.{key}"


def general_fixture(timezone: str, now: datetime | None = None) -> list[dict[str, object]]:
    tz = ZoneInfo(timezone)
    now = datetime.now(tz) if now is None else now.astimezone(tz)
    today = now.replace(hour=0, minute=0, second=0, microsecond=0)
    thu = today + timedelta(days=(3 - today.weekday()) % 7)
    if thu == today and now.hour >= 14:
        thu += timedelta(days=7)
    result = []
    for title, location, start, end in [
        ("Thesis committee meeting", "Benedum 1234", now - timedelta(minutes=30), now + timedelta(minutes=90)),
        ("Dentist appointment", "Oakland Dental, 3500 Forbes Ave", thu.replace(hour=13, minute=30), thu.replace(hour=15)),
        ("CS 2610 project due", "", (today + timedelta(days=1)).replace(hour=23, minute=59),
         (today + timedelta(days=1)).replace(hour=23, minute=59)),
        ("Flight to Austin (MobiCom)", "PIT -> AUS", (today + timedelta(days=2)).replace(hour=7, minute=15),
         (today + timedelta(days=2)).replace(hour=11, minute=40)),
    ]:
        result.append({"title": title, "eventLocation": location,
                       "dtstart": int(start.timestamp() * 1000), "dtend": int(end.timestamp() * 1000),
                       "eventTimezone": timezone})
    return result


class Phone:
    def __init__(self, adb: str, serial: str):
        self.adb, self.serial = adb, serial

    def shell(self, *args: str) -> str:
        completed = subprocess.run([self.adb, "-s", self.serial, "shell", shlex.join(args)],
                                   stdin=subprocess.DEVNULL, capture_output=True, text=True,
                                   encoding="utf-8", timeout=30)
        output = completed.stdout.replace("\r", "")
        if completed.returncode or re.search(r"\b(?:Error|Exception|SecurityException|Permission Denial)\b",
                     output + completed.stderr, re.IGNORECASE):
            raise RuntimeError((output + completed.stderr).strip())
        return output

    def content(self, *args: str) -> str:
        return self.shell("content", *args)

    def query(self, uri: str, projection: list[str], where: str) -> list[dict[str, str]]:
        return parse_rows(self.content("query", "--uri", uri, "--projection", ":".join(projection),
                                       "--where", where), projection)

    def calendars(self) -> list[dict[str, str]]:
        names = ",".join(f"'{name}'" for name in [ACCOUNT, *LEGACY])
        rows = self.query(CALENDARS, CALENDAR_COLUMNS,
                          f"account_type='LOCAL' AND account_name IN ({names})")
        seen = set()
        for row in rows:
            account = row["account_name"]
            if account in seen or row["name"] != account or row["ownerAccount"] != account:
                raise RuntimeError("Demo account has ambiguous or unexpected metadata; no calendars were removed")
            seen.add(account)
        return rows

    def owned_calendar(self) -> int | None:
        ids = [int(row["_id"]) for row in self.calendars() if row["account_name"] == ACCOUNT]
        return ids[0] if ids else None

    def ensure_calendar(self) -> int:
        calendars = self.calendars()
        if any(row["account_name"] in LEGACY for row in calendars):
            raise RuntimeError("Legacy scenario calendars remain; run tools/demo_calendar.py --merge first")
        calendar_id = self.owned_calendar()
        if calendar_id is None:
            self.content("insert", "--uri", self.sync_uri(CALENDARS, ACCOUNT),
                         "--bind", f"account_name:s:{ACCOUNT}", "--bind", "account_type:s:LOCAL",
                         "--bind", f"name:s:{ACCOUNT}", "--bind", f"ownerAccount:s:{ACCOUNT}",
                         "--bind", f"calendar_displayName:s:{DISPLAY_NAME}",
                         "--bind", "calendar_color:i:-14575885", "--bind", "calendar_access_level:i:700",
                         "--bind", "visible:i:1", "--bind", "sync_events:i:1")
            calendar_id = self.owned_calendar()
        if calendar_id is None:
            raise RuntimeError("Calendar creation did not produce the shared demo calendar")
        return calendar_id

    @staticmethod
    def sync_uri(uri: str, account: str = ACCOUNT) -> str:
        suffix = "?caller_is_syncadapter=true"
        if account is not None:
            suffix += f"&account_name={account}&account_type=LOCAL"
        return uri + suffix

    def read_events(self, calendar_id: int) -> list[dict[str, object]]:
        rows = self.query(EVENTS, EVENT_COLUMNS, f"calendar_id={calendar_id}")
        return sorted(({key: int(value) if key in NUMERIC and value != "NULL" else value
                        for key, value in row.items()} for row in rows),
                      key=lambda event: (event["dtstart"], event["_id"]))

    def scenario_events(self, calendar_id: int, scenario: str) -> list[dict[str, object]]:
        return [event for event in self.read_events(calendar_id)
                if str(event["_sync_id"]).startswith(f"{PREFIX}{scenario}.")]

    def seed_scenario(self, scenario: str, events: list[dict[str, object]]) -> int:
        calendar_id = self.ensure_calendar()
        before = self.read_events(calendar_id)
        existing = {}
        for event in self.scenario_events(calendar_id, scenario):
            key = event["_sync_id"]
            if key in existing:
                raise RuntimeError("Duplicate scenario fixture IDs; refusing refresh")
            existing[key] = event
        if len(events) != len(SCENARIO_KEYS[scenario]):
            raise ValueError("Fixture key count does not match the scenario")
        expected_ids = []
        for key, event in zip(SCENARIO_KEYS[scenario], events):
            sync_id = fixture_id(scenario, key)
            expected_ids.append(sync_id)
            values = {**event, "calendar_id": calendar_id, "_sync_id": sync_id,
                      "allDay": 0, "hasAlarm": 0, "deleted": 0}
            args = ["update" if sync_id in existing else "insert", "--uri", self.sync_uri(EVENTS)]
            if sync_id in existing:
                args += ["--where", f"_id={existing[sync_id]['_id']} AND calendar_id={calendar_id}"]
            for column, value in values.items():
                kind = "l" if isinstance(value, int) else "s"
                args += ["--bind", f"{column}:{kind}:{value}"]
            self.content(*args)
        for sync_id, event in existing.items():
            if sync_id not in expected_ids:
                self.content("delete", "--uri", self.sync_uri(EVENTS), "--where",
                             f"_id={event['_id']} AND calendar_id={calendar_id}")
        untouched = [event for event in before if not str(event["_sync_id"]).startswith(f"{PREFIX}{scenario}.")]
        after = {event["_id"]: event for event in self.read_events(calendar_id)}
        if any(after.get(event["_id"]) != event for event in untouched):
            raise RuntimeError("A non-scenario event changed during refresh")
        return calendar_id

    def snapshot(self) -> dict[str, object]:
        calendars = self.calendars()
        events = [event for calendar in calendars for event in self.read_events(int(calendar["_id"]))]
        children = {}
        for table, columns in [("reminders", ["_id", "event_id", "minutes", "method"]),
                               ("attendees", ["_id", "event_id", "attendeeName", "attendeeEmail",
                                              "attendeeRelationship", "attendeeType", "attendeeStatus"]),
                               ("extendedproperties", ["_id", "event_id", "name", "value"])]:
            ids = ",".join(str(event["_id"]) for event in events)
            children[table] = self.query(f"content://com.android.calendar/{table}", columns,
                                         f"event_id IN ({ids})") if ids else []
        return {"serial": self.serial, "calendars": calendars, "events": events, "children": children}

    def merge(self, backup_dir: Path) -> dict[str, object]:
        before = self.snapshot()
        markers = [event["_sync_id"] for event in before["events"]
                   if str(event["_sync_id"]).startswith(PREFIX)]
        if len(markers) != len(set(markers)):
            raise RuntimeError("Duplicate demo fixture identities; inspect them before merging")
        backup_dir.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now(utc_timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        safe_serial = re.sub(r"[^A-Za-z0-9_.-]", "_", self.serial)
        backup = backup_dir / f"{stamp}-{safe_serial}-{uuid.uuid4().hex[:8]}.json"
        backup.write_text(json.dumps(before, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        canonical = [row for row in before["calendars"] if row["account_name"] == ACCOUNT]
        if canonical:
            calendar_id = int(canonical[0]["_id"])
        else:
            self.content("insert", "--uri", self.sync_uri(CALENDARS, ACCOUNT),
                         "--bind", f"account_name:s:{ACCOUNT}", "--bind", "account_type:s:LOCAL",
                         "--bind", f"name:s:{ACCOUNT}", "--bind", f"ownerAccount:s:{ACCOUNT}",
                         "--bind", f"calendar_displayName:s:{DISPLAY_NAME}",
                         "--bind", "calendar_color:i:-14575885", "--bind", "calendar_access_level:i:700",
                         "--bind", "visible:i:1", "--bind", "sync_events:i:1")
            calendar_id = self.owned_calendar()
            if calendar_id is None:
                raise RuntimeError("Shared calendar creation failed; old calendars remain")
        accounts = {int(row["_id"]): row["account_name"] for row in before["calendars"]}
        for event in before["events"]:
            source = int(event["calendar_id"])
            if source != calendar_id:
                self.content("update", "--uri", self.sync_uri(EVENTS, accounts[source]), "--where",
                             f"_id={event['_id']} AND calendar_id={source}",
                             "--bind", f"calendar_id:l:{calendar_id}")
        after = self.snapshot()
        by_id = {event["_id"]: event for event in after["events"]}
        if set(by_id) != {event["_id"] for event in before["events"]}:
            raise RuntimeError(f"Event IDs changed during merge; no calendars removed. Backup: {backup}")
        for event in before["events"]:
            actual = by_id[event["_id"]]
            if actual["calendar_id"] != calendar_id or any(actual[key] != event[key] for key in PAYLOAD_COLUMNS):
                raise RuntimeError(f"Event payload changed during merge; no calendars removed. Backup: {backup}")
        if after["children"] != before["children"]:
            raise RuntimeError(f"Event reminders/attendees changed; no calendars removed. Backup: {backup}")
        # Mark only recognizable script fixtures. An extra hand-added event (such
        # as Test event) moves with the calendar but remains outside every refresh.
        expected_markers = {}
        for event in before["events"]:
            if event["_sync_id"] != "NULL":
                continue
            scenario = LEGACY.get(accounts[int(event["calendar_id"])], "general")
            key = legacy_fixture_key(scenario, event)
            if key is not None:
                sync_id = fixture_id(scenario, key)
                conflict = [row for row in self.read_events(calendar_id) if row["_sync_id"] == sync_id]
                if conflict and conflict[0]["_id"] != event["_id"]:
                    raise RuntimeError(f"Duplicate fixture identity; keep backup {backup} and inspect before reseeding")
                self.content("update", "--uri", self.sync_uri(EVENTS), "--where",
                             f"_id={event['_id']} AND calendar_id={calendar_id}", "--bind", f"_sync_id:s:{sync_id}")
                expected_markers[event["_id"]] = sync_id
        marked = {event["_id"]: event for event in self.read_events(calendar_id)}
        if any(marked.get(event_id, {}).get("_sync_id") != marker for event_id, marker in expected_markers.items()):
            raise RuntimeError(f"Fixture markers did not persist; no calendars removed. Backup: {backup}")
        removed = []
        for calendar in before["calendars"]:
            old_id = int(calendar["_id"])
            if old_id == calendar_id:
                continue
            if self.read_events(old_id):
                raise RuntimeError("A legacy calendar gained events during merge; refusing to remove it")
            current = {int(row["_id"]): row for row in self.calendars()}
            if current.get(old_id) != calendar:
                raise RuntimeError("Legacy calendar metadata changed during merge; refusing removal")
            self.content("delete", "--uri", self.sync_uri(CALENDARS, calendar["account_name"]),
                         "--where", f"_id={old_id}")
            removed.append(old_id)
        result = self.snapshot()
        result.update({"calendar_id": calendar_id, "backup": str(backup.resolve()),
                       "removed_empty_calendar_ids": removed, "event_ids_preserved": True,
                       "event_count": len(result["events"]), "status": "merged"})
        return result


def legacy_fixture_key(scenario: str, event: dict[str, object]) -> str | None:
    if event["deleted"] or event["allDay"] or event["rrule"] != "NULL":
        return None
    if scenario in {"hotel", "schedule"}:
        # Full fixture equality avoids claiming an event merely because its title
        # resembles one of our examples.
        from demo_hotel_calendar import fixture as hotel
        from demo_schedule_calendar import fixture as schedule
        for key, expected in zip(SCENARIO_KEYS[scenario], hotel() if scenario == "hotel" else schedule()):
            if all(event[column] == value for column, value in expected.items()):
                return key
        return None
    expected = {
        "general": [("meeting", "Thesis committee meeting", "Benedum 1234"),
                    ("dentist", "Dentist appointment", "Oakland Dental, 3500 Forbes Ave"),
                    ("deadline", "CS 2610 project due", ""),
                    ("flight", "Flight to Austin (MobiCom)", "PIT -> AUS")],
        "callback": [("meeting", "MobileClaw demo meeting", "Fictional demo room"),
                     ("review", "MobileClaw demo review", "Fictional demo room")],
    }
    for key, title, location in expected[scenario]:
        actual_location = "" if event["eventLocation"] == "NULL" else event["eventLocation"]
        if event["title"] == title and actual_location == location:
            return key
    return None


def scenario_report(phone: Phone, calendar_id: int | None, scenario: str,
                    expected: list[dict[str, object]], timezone: str, mode: str) -> dict[str, object]:
    events = [] if calendar_id is None else phone.scenario_events(calendar_id, scenario)
    by_key = {event["_sync_id"]: event for event in events}
    matches = len(events) == len(expected) and len(by_key) == len(events)
    for key, fixture in zip(SCENARIO_KEYS[scenario], expected):
        event = by_key.get(fixture_id(scenario, key))
        matches = matches and event is not None and all(event[column] == value for column, value in fixture.items())
        matches = matches and event is not None and event["allDay"] == 0 and event["deleted"] == 0
    tz = ZoneInfo(timezone)
    for event in events:
        event["start_local"] = datetime.fromtimestamp(event["dtstart"] / 1000, tz).isoformat()
        event["end_local"] = datetime.fromtimestamp(event["dtend"] / 1000, tz).isoformat()
    return {"mode": mode, "serial": phone.serial, "account_name": ACCOUNT, "account_type": ACCOUNT_TYPE,
            "calendar_name": ACCOUNT, "calendar_id": calendar_id, "scenario": scenario, "timezone": timezone,
            "matches_fixture": bool(matches), "events": events, "expected_events": expected}
