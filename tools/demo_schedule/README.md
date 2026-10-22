# Schedule-conflict SMS rehearsal

This rehearsal supports an Android 16 LineageOS OnePlus 12R and uses local inbound
SMS-DELIVER injection into real Messaging, a live model and production tools.
The case ends at a **visible reply confirmation**, then revokes it without
sending. No calendar event is created. Carrier receipt remains separate acceptance.

## Fixture

Fictional peer Sam Demo uses reserved sender `+12025550144` (shown by number unless a
demo contact is added). SMS, 68 UCS-2 characters:

> Can we sync Oct 28, 2-2:30pm? If busy, suggest the next 30-min slot.

Select **MobileClaw demo** (`LOCAL / MobileClawDemo`) in **Settings → Calendar**.
Hotel, schedule, callback and general scenarios share this source. These are its three
schedule fixtures on **Wednesday, October 28, 2026**, America/Chicago:

| Event | Start | End |
|---|---|---|
| Poster review | 13:30 | 14:30 |
| Demo setup | 14:30 | 15:00 |
| Research meeting | 15:30 | 16:30 |

`read_calendar` reports actual BEGIN **and END**, including the year for both
timestamps. The foreground turn also receives the actual current device date, offset
and timezone to resolve date references in the request. Clock precision
is whole seconds so nanosecond digits do not trigger OTP redaction. The normal calendar
scope defaults to All calendars; this rehearsal selects the shared LOCAL demo calendar.
The preflight verifies the three schedule fixtures in the queried day and permits other
scenarios' events. The seeder refreshes only its marked schedule fixtures and preserves
other events, including manually added test events. For an existing phone, first follow
[Shared demo calendar](../demo_calendar/README.md) to merge the former demo calendars.

The requested 14:00–14:30 conflicts with Poster review. 14:30–15:00 is also occupied.
The first full half-hour gap is **15:00–15:30**, ending when Research meeting starts.
The SMS and foreground instruction supply none of these answers.

## Interaction and validation

1. New native SMS arrives while the agent is Running. The read-only follow-up identifies
   the conflict with `read_calendar` and offers action chips. The short bubble may only
   describe the conflict; a candidate slot can appear in a chip instead. Do not claim
   that every initial bubble searches the whole afternoon.
2. Reject triage's original holding reply (**Not now**) if it is shown by this runner.
3. The explicit user request for the tested reply phase is:

   > Read my full afternoon calendar for that day to verify the next free 30-minute slot before drafting a reply. Briefly explain the conflict and propose the verified slot with both start and end times. Use the notification reply action and ask me to confirm before sending. Do not add a calendar event yet.

4. The foreground trace must contain its own `read_calendar` result with **all three
   2026 intervals**, not merely a copy of background results. Then `reply_notification`
   stages a draft proposing 15:00–15:30 and explaining the conflict.
5. Show the real **Confirm action** dialog. An example confirmation is:

   > I am busy from 1:30 PM to 3:00 PM on October 28th. Would 3:00 PM to 3:30 PM work for you instead?

6. Stop before **Do it**. The runner cancels/drains jobs, revokes proposals and clicks
   only negative buttons. It restores trace/follow-up/automation/run state. A failed
   drain leaves automation OFF and the action gate blocked until the app is restarted.

## Prepare native SMS state before switching scenarios

**Reset demo does not mark old native SMS read.** Unread fictional conversations can
make Messaging re-post hotel/summary notifications and consume its package's five-minute
follow-up window. Open the fixture conversations on an awake phone to mark them read;
changing provider read flags alone does not refresh Messaging's notification state.

Use `prepare_fixture_threads.py` before the next run. It stops MobileClaw, wakes/unlocks
the phone, opens only the two reserved fictional conversations and waits for their real
screens to resume. It supplies no message body, presses no send button, deletes nothing
and needs no shell WRITE_SMS override. It reports remaining Messaging keys and fails if
any remain; **do not clear personal messages/notifications to force success**. The live
runner subsequently starts MobileClaw and rebinds its authorized listener.

## Reproduction

Prepare the [demo corpus](../demo_preload/README.md), native notification access,
POST_NOTIFICATIONS, READ_CALENDAR and the app's requested permissions. Injection borrows
MODIFY_PHONE_STATE/READ_PHONE_STATE only, and drops them before model/tool calls. It needs
an active SMS subscription and compatible native Messaging behavior; check these on a new
phone. Configure a working model endpoint and network, use America/Chicago for the fixed
fixture, and preserve/restore the original timezone and automatic-timezone settings.
Do not change the device clock.

```sh
python3 tools/demo_schedule_calendar.py --serial SERIAL --adb /path/to/adb
bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 tools/demo_schedule/prepare_fixture_threads.py --serial SERIAL --adb /path/to/adb
adb -s SERIAL shell am instrument -w -r \
  -e class 'edu.pitt.isl.mobileclaw.notiftest.demo.ScheduleRehearsalRunner#selectAndVerifyDemoCalendarWithoutModel' \
  -e scheduleCalendarScope true \
  edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner

# Separate terminal: save the live trace before the test starts.
adb -s SERIAL logcat -T 1 -v brief -s MobileClaw-TRACE:I MobileClaw-SCHEDULE:I
adb -s SERIAL shell am instrument -w -r \
  -e class 'edu.pitt.isl.mobileclaw.notiftest.demo.ScheduleRehearsalRunner#incomingSmsFindsNextFreeSlot' \
  -e scheduleRehearsal true -e scheduleRunId SCHEDULE-NEW-UNIQUE \
  edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL exec-out run-as edu.pitt.isl.mobileclaw.notiftest \
  cat files/demo-schedule/SCHEDULE-NEW-UNIQUE/report.json
```

Require OK (1 test), `status: passed`, `finalCalendarIntervalsVerified: true`,
`confirmationDialogVisible: true`, `cleanupDrained: true`, settings/run-state restored,
and zero successful outbound **and calendar-write** audit rows. Inspect source results
and actual screenshot for semantic correctness; the time-range regex alone is insufficient.

## Recording options

`-e scheduleRecordingHoldMs 7000` defaults to zero and accepts 0–8000 ms. It holds the
incoming event, suggestion and confirmation on screen. `phaseTimings` and `startedWallMs`
align the report with a screen recording; these holds are not model latency.

`-e scheduleRecordingHome true` requires a positive hold and adds Home before injection.
With `-e scheduleShortRequest true` and a positive hold, the runner types this shorter
request into the actual foreground input:

> Check availability, then reply with the next free half-hour.

The live foreground trace must still contain its own `read_calendar` result with all
three fixture intervals. The final confirmation must propose 15:00–15:30 and the runner
must leave outbound/calendar-write counts unchanged. Personal memory injection fails
this fictional rehearsal before the incoming event is injected. Individual fixture
passes do not establish a general model success rate.
