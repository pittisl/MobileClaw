# Hotel SMS rehearsal on a physical phone

This is a locally injected **inbound** SMS followed by the production notification,
triage, contextual follow-up and automatically proposed reply. It uses a live Gemini model.
It does not demonstrate carrier delivery or approve an outbound reply.

## Fixture and expected result

Sender: fictional `+12025550143`. Fixture body (68 UCS-2 characters):

> Cedar House PDF booking code? Is our Oct 25 arrival before check-in?

The message contains no booking code, arrival hour or check-in hour. The two hotel
events are in the shared unsynced **MobileClaw demo** calendar
(`LOCAL / MobileClawDemo`), on October 25, 2026, in **America/Chicago**:

| Event | Time | Location |
|---|---|---|
| Arrive in Austin — UA1234 | 11:40–12:00 | AUS |
| Planned arrival at Cedar House Austin | 12:30–12:45 | Cedar House Austin |

`/sdcard/Download/MobileClawDemo/hotel-confirmation.pdf` contains **LKVQ7X** and
standard check-in **after 15:00**. These facts must come from its real OCR search
result. The boarding-pass image is optional and contains no arrival time. Luggage
storage is optional only if the relevant passage is actually retrieved.

Expected comparison: arrival at **12:30 precedes 15:00**. Early check-in needs hotel
confirmation; do not promise that a room will be ready. The automatically proposed
reply must contain the code and both times, without any foreground USER request.
Only one contextual reply is proposed, replacing the unverified triage draft.
Tool order is chosen by the model.

## Calendar isolation and reset

**Settings → Calendar** selects one calendar for every `read_calendar` call,
including background follow-up, and every confirmed event addition. Default remains
**All calendars**, which supports reads only. Invalid, missing or read-only write
destinations do not fall back to other calendars. **Reset demo** preserves this selection
and the actual events.

Before live inference, select **MobileClaw demo** and run the offline preflight.
Hotel, schedule, callback and general scenarios use that same calendar; no source
switch is needed between them. The preflight checks the two hotel fixture events in
their query window and permits the other scenarios' events. The hotel seeder refreshes
only its marked fixtures, preserving other events, including manually added test events.
Personal calendars are retained. Other app data is not made into a clean demo profile
by this setting; complete dedicated-account setup before the booth.

For an existing phone, first follow [Shared demo calendar](../demo_calendar/README.md)
to merge the old LOCAL demo calendars.

Reset order matters for a repeated SMS thread: **Running/resume-reseed → Reset demo →
new SMS**. Reseeding after reset fills stale startup keys again; never reseed after the
incoming SMS. The runner clears cooldown/dedup, uses a fresh postTime and a newly dated
triage row, and starts an isolated chat. Other chats and audit history remain.

## Reproduction

Use an explicit serial throughout. This fixture supports a LineageOS OnePlus 12R
with Android 16 and native Messaging (`com.android.messaging`). Root is not needed:
only the injection borrows shell `MODIFY_PHONE_STATE` and `READ_PHONE_STATE`; identity is dropped before model tools run. Grant actual notification
access, `POST_NOTIFICATIONS`, `READ_CALENDAR` and all-files access. Prepare the corpus
using [demo_preload](../demo_preload/README.md) and rebuild its search index.
Configure a working model endpoint and network before the live run. Preserve existing
network settings; the rehearsal does not require a particular mobile carrier or DNS.
Device-specific injection and messaging behavior should be checked on a new phone.

The fixture assumes Austin time. Temporarily select **America/Chicago**, saving the
original timezone and automatic-timezone setting and restoring them afterward. Do not
change the clock. At the booth, regenerate future coherent fixture dates as needed.

```sh
# Refresh only hotel fixtures in MobileClaw demo; --check-only reads without changes.
python3 tools/demo_hotel_calendar.py --serial SERIAL --adb /path/to/adb
bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Explicit offline setup; leaves Calendar on shared MobileClaw demo.
adb -s SERIAL shell am instrument -w -r \
  -e class 'edu.pitt.isl.mobileclaw.notiftest.demo.HotelRehearsalRunner#selectAndVerifyDemoCalendarWithoutModel' \
  -e hotelCalendarScope true \
  edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner

# Separate terminal: save live trace before starting the model run.
adb -s SERIAL logcat -T 1 -v brief -s MobileClaw-TRACE:I MobileClaw-HOTEL:I

# Every runId must be a fresh HOTEL-... identifier. No approval is performed.
adb -s SERIAL shell am instrument -w -r \
  -e class 'edu.pitt.isl.mobileclaw.notiftest.demo.HotelRehearsalRunner#incomingSmsConsultsCalendarAndHotelPdf' \
  -e hotelRehearsal true -e hotelRunId HOTEL-NEW-UNIQUE \
  edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL exec-out run-as edu.pitt.isl.mobileclaw.notiftest \
  cat files/demo-hotel/HOTEL-NEW-UNIQUE/report.json
```

Require **OK (1 test)**, `status: passed`, `confirmationDialogVisible: true`,
`automaticPdfOcrVerified: true`, `userMessageCount: 0`, `replyProposalCount: 1`,
`cleanupDrained: true`, settings/run-state restored,
and zero successful outbound audit rows. Also inspect the actual tool results and
confirmation screenshot; string checks alone do not prove the conclusion is sensible.
All normal test runs skip the opt-in model test. The runner writes reports and PNGs
under app-private `files/demo-hotel/RUNID/`.

For recording, start the phone video capture and live trace **before** invoking the
runner, and add `-e hotelRecordingHoldMs 4000` to the live instrument command above.
This optional integer defaults to **0**, accepts **0–8000 milliseconds**, and rejects
malformed or out-of-range values before changing phone state. It holds each real UI
at the actual confirmation, giving the editor readable footage before cleanup. With
`hotelRecordingHome=true`, Home is shown before injection and the real incoming banner
is held for at most 2.5 seconds before returning to the app. This lets the automatic
proposal normally arrive on the foreground confirmation surface. It never approves or
sends the reply. Do not replace failed checks with screenshots or another take.

The trace logs incoming, app-opened, follow-up and
`confirmation-visible` / `confirmation-hold-finished` with `elapsedMs` relative to
the runner's start. `report.json` retains all phase names and relative times in
`phaseTimings`, plus `startedWallMs` and `recordingHoldMs`, to locate those intervals
in the raw recording. Holds are included in `elapsedMs`; do not report a recording
run's total duration as model latency. Normal cancellation, proposal revocation and
settings restoration still run after the final hold.

## What the visitor sees

1. A genuine Messaging SMS notification arrives while MobileClaw is Running.
2. The laptop trace shows triage and automatic `search_files`/`read_calendar` calls.
3. Without a foreground request, the phone proposes a contextual reply through
   `draft_notification_reply`. No original triage draft is separately proposed.
4. Show **Confirm action**, including the code and time comparison. Stop before
   **Do it**. The automated rehearsal cancels/drains work, revokes proposals and clicks
   only **Not now**; it restores trace/follow-up/automation settings and run state.

The calendar selection is intentionally retained. If cleanup cannot drain tasks, the
runner leaves automation OFF and the action gate blocked; inspect its report and restart
the app before another run. It never calls a proposal's execute function.

Inbound SMS fixtures and isolated rehearsal chats remain for inspection. **Reset demo**
does not delete native SMS, remove the demo calendar or undo an approved message. Only
remove clearly identified fixture rows when cleaning up. Carrier delivery and sending an
approved real reply need separate validation. A successful fixture run does not measure
model reliability for arbitrary incoming messages.

## Follow-up behavior

Document and personal-timing questions in an English notification establish source
requirements for the contextual draft. The bounded follow-up turn reads those sources
and calls `draft_notification_reply`, or explicitly holds the reply. The draft is bound
to the original notification key/postTime and requires confirmation even when Full
Automation is enabled. Notification text stays in context rather than becoming a USER
turn. No memory-writing tools are available to this follow-up.

`-e hotelRecordingHome true` requires a positive `hotelRecordingHoldMs`. It shows Home
before injection, then returns to the existing Activity/ViewModel. Review `phaseTimings`
in the report alongside the capture; recording holds are presentation time, not model
latency.
