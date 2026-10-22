# Shared demo calendar

All MobileClaw demo scenarios use one unsynced Android calendar:

| Field | Value |
|---|---|
| Display name | MobileClaw demo |
| Account type | LOCAL |
| Account / name / owner | MobileClawDemo |

Select **MobileClaw demo** once in **MobileClaw → Settings → Calendar**.
General, hotel, schedule, callback and mail-to-TaskBoard rehearsals use that source.
Keep it selected between scenarios. Both calendar reads and confirmed event additions
use this selection. Personal Google calendars remain separate. **All calendars**
supports reads only; choose one writable calendar before adding an event.

## Merge an existing phone

Use an explicit serial so another connected phone is not changed:

```sh
python3 tools/demo_calendar.py --serial SERIAL --check-only
python3 tools/demo_calendar.py --serial SERIAL --merge
python3 tools/demo_calendar.py --serial SERIAL --check-only
```

The migration recognizes only the owned LOCAL accounts `MobileClawDemo`,
`MobileClawHotelDemo`, `MobileClawScheduleDemo` and `MobileClawCallbackDemo`. It refuses
ambiguous or unexpected calendar metadata. It backs up their calendar/event rows before
making changes, reuses the existing `MobileClawDemo` calendar when present, and moves
events by changing their calendar id. Event ids and event content are preserved.
After verifying the moved rows, it deletes only the empty legacy demo calendars.
It does not delete and recreate events or touch Google calendars. A manually added
event in one of the old demo calendars moves with it.

`--check-only` reads and reports the current calendar state without changing the phone.
Backups are saved under `tools/demo_calendar/backups/` as
`<UTCtimestamp>-<serial>-<uuid>.json` and are not tracked by git. Inspect the migration
report and keep its backup before rehearsing. Scripts identify the shared calendar by
account metadata rather than relying on a device-specific id.

The migration does not edit the running app's settings. Select the shared source once
in Settings, or install the current test APK and run this offline setup:

```sh
adb -s SERIAL shell am instrument -w -r \
  -e class 'edu.pitt.isl.mobileclaw.notiftest.demo.SharedDemoCalendarRunner#selectAndVerifyMergedCalendarWithoutModel' \
  -e demoCalendarScope true \
  edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner
```

This commits the selection and verifies the real `read_calendar` result without an LLM
call. Its report is `files/demo-calendar/calendar-scope.json` in app-private storage.
An optional `-e demoCalendarExpectedEventIds ID1,ID2,...` checks that specified original
event ids remain readable after migration. The scenario READMEs contain APK build/install
commands. Pass `--adb /path/to/adb` to the Python tools if adb is not on the host PATH.

## Refresh the scenario you need

The existing entry points remain:

```sh
# General notification scenarios: meeting now, dentist, deadline and flight.
bash tools/demo_calendar.sh SERIAL
# Equivalent Python entry point, with an optional explicit timezone:
python3 tools/demo_calendar.py --serial SERIAL --seed general --timezone America/New_York

# Fixed October 25 hotel fixtures (America/Chicago).
python3 tools/demo_hotel_calendar.py --serial SERIAL

# Fixed October 28 schedule fixtures (America/Chicago).
python3 tools/demo_schedule_calendar.py --serial SERIAL
```

Each seeder refreshes only the fixtures marked for its own scenario in **MobileClaw
demo**. Other scenarios and manually added events remain. Repeated seeding must not
replace the whole calendar. Hotel and schedule scripts also accept `--check-only` for
read-only checks. The callback runner refreshes only its marked current-meeting/review
fixtures; it leaves the shared calendar selected for the next scenario.

The hotel and schedule offline runners select/verify the same shared source. Their
preflights verify the required scenario events and queried time window, permitting
the other scenarios' events in the calendar. See the corresponding scenario README
for the current commands and acceptance requirements.

## Verify before going on stage

1. Run `--check-only` and confirm there is one owned demo calendar, with no legacy
   demo calendar left after migration. Check that manually added events remain.
2. Check **Settings → Calendar → MobileClaw demo**. **Reset demo** preserves
   that selection and the actual calendar rows.
3. Run the scenario's offline preflight before invoking a live model.
4. Ask for the scenario's actual date. Hotel uses October 25, schedule uses October 28,
   general fixtures are relative to the rehearsal date and callback uses the current
   meeting. A query for today correctly omits future fixtures.
5. Confirm the device timezone matches the scenario. The hotel/schedule fixtures use
   America/Chicago; do not change the device clock to make a fixture appear current.

The device-free migration and refresh checks are in `tools/test_demo_calendar.py`:

```sh
python3 -m unittest discover -s tools -p test_demo_calendar.py
```

A passing host test does not establish the state of a connected phone. Use the actual
provider report and selected-calendar reader checks above when preparing that phone.
