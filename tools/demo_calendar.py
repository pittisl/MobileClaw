#!/usr/bin/env python3
"""Merge old demo calendars, inspect the shared calendar, or refresh general fixtures."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys

from demo_calendar_common import LEGACY, Phone, general_fixture, scenario_report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--merge", action="store_true", help="Back up, move events in place and remove empty legacy calendars")
    mode.add_argument("--check-only", action="store_true", help="Inspect without changing events or app preferences")
    mode.add_argument("--seed", choices=["general"], help="Refresh only this scenario's marked fixtures")
    parser.add_argument("--timezone", help="General fixture timezone; default is the phone's current timezone")
    parser.add_argument("--backup-dir", type=Path, default=Path(__file__).resolve().parent / "demo_calendar" / "backups")
    args = parser.parse_args()
    phone = Phone(args.adb, args.serial)
    try:
        if args.merge:
            result = phone.merge(args.backup_dir)
        elif args.seed:
            timezone = args.timezone or phone.shell("getprop", "persist.sys.timezone").strip()
            expected = general_fixture(timezone)
            calendar_id = phone.seed_scenario("general", expected)
            result = scenario_report(phone, calendar_id, "general", expected, timezone, "seed")
            if not result["matches_fixture"]:
                raise RuntimeError("General fixture refresh did not verify")
        else:
            result = phone.snapshot()
            calendar_id = phone.owned_calendar()
            legacy = [row["account_name"] for row in result["calendars"] if row["account_name"] in LEGACY]
            result.update({"calendar_id": calendar_id, "legacy_calendars": legacy,
                           "event_count": len(result["events"]), "status": "shared" if calendar_id and not legacy else "needs_merge"})
        result["selection_note"] = "Select MobileClaw demo once, or run SharedDemoCalendarRunner's offline selection entry point."
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1 if result.get("status") == "needs_merge" else 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(json.dumps({"serial": args.serial, "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
