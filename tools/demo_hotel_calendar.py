#!/usr/bin/env python3
"""Seed or inspect the hotel fixture in the shared LOCAL / MobileClawDemo calendar.

Only this scenario's marked events are refreshed. Other scenarios and hand-added
calendar events are preserved. Merge legacy calendars with demo_calendar.py first.
"""
from __future__ import annotations

import argparse
from datetime import datetime
import json
import subprocess
import sys
from zoneinfo import ZoneInfo

from demo_calendar_common import ACCOUNT, ACCOUNT_TYPE, Phone as SharedPhone, scenario_report

TIMEZONE = "America/Chicago"


def fixture() -> list[dict[str, object]]:
    tz = ZoneInfo(TIMEZONE)
    result = []
    for title, location, start, end in [
        ("Arrive in Austin — UA1234", "AUS", "11:40", "12:00"),
        ("Planned arrival at Cedar House Austin", "Cedar House Austin", "12:30", "12:45"),
    ]:
        begin = datetime.fromisoformat(f"2026-10-25T{start}:00").replace(tzinfo=tz)
        finish = datetime.fromisoformat(f"2026-10-25T{end}:00").replace(tzinfo=tz)
        result.append({
            "title": title, "eventLocation": location,
            "dtstart": int(begin.timestamp() * 1000),
            "dtend": int(finish.timestamp() * 1000),
            "eventTimezone": TIMEZONE,
        })
    return result


class Phone(SharedPhone):
    def seed(self) -> int:
        return self.seed_scenario("hotel", fixture())


def report(phone: Phone, calendar_id: int | None, mode: str) -> dict[str, object]:
    return scenario_report(phone, calendar_id, "hotel", fixture(), TIMEZONE, mode)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit adb serial of the rehearsal phone")
    parser.add_argument("--adb", default="adb", help="adb executable path (default: adb on PATH)")
    parser.add_argument("--check-only", action="store_true",
                        help="Read and verify this scenario's two marked events without modifying the phone")
    args = parser.parse_args()
    phone = Phone(args.adb, args.serial)
    try:
        calendar_id = phone.owned_calendar() if args.check_only else phone.seed()
        result = report(phone, calendar_id, "check" if args.check_only else "seed")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result["matches_fixture"] else 1
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(json.dumps({"serial": args.serial, "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
