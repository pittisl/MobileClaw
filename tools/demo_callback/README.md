# Missed call → callback task through AppFunctions

This Android 16 rehearsal was developed on a LineageOS OnePlus 12R.
It tests a managed local incoming call through Android Telecom, the native Dialer
missed-call notification, production notification capture, foreground reasoning,
visible confirmation and one real TaskBoard AppFunctions write. It does not place
an outbound call or send SMS. Carrier delivery is a separate acceptance check.

## Visitor flow

1. A fictional **Morgan Demo**, reserved number **+1 202-555-0145**, calls while the
   user is in a meeting. The test ConnectionService rings for six seconds and ends
   with `DisconnectCause.MISSED`; Telecom/Dialer must generate the notification.
2. The user asks the foreground assistant to look up the caller and calendar and
   prepare a callback task for the first free 15-minute slot after the meeting.
3. The assistant proposes TaskBoard `addTask`, including number, date, time range,
   timezone and the missed call as source. The real confirmation dialog appears.
4. Only after validation, the runner clicks the visible **Do it**. Android's actual
   AppFunctions bus invokes TaskBoard on this same phone; the new task persists.

The exact user instruction contains no caller name, number or computed callback time:

> Read the latest demo missed-call notification, look up that caller in contacts and check my calendar. Add one callback task to TaskBoard for the first free 15-minute slot after my current meeting. Include the caller's number, the slot date in YYYY-MM-DD, BOTH its start AND end in 24-hour HH:mm format, the IANA timezone, and the missed call as source in the note. Ask me to confirm before adding. Do not call or send a message.

This is an **explicit foreground** workflow. Background triage is paused; its existing
Call Back action must not be approved. The production listener's
resume/reseed captures active notifications without reasoning. Test input selection
keeps only the fixture's original captured object, so personal notifications do not
enter this turn. No synthetic `NotificationManager.notify` substitutes for Dialer.

## Isolated context

The runner creates/reuses a local contact with the exact fictional name/number and
refuses an ambiguous or mismatching contact. It uses the shared **MobileClaw demo**
calendar (`LOCAL / MobileClawDemo`). Only its marked callback fixtures are refreshed;
hotel, schedule, general and manually added events remain. It never changes personal
contacts/calendars or the device clock/timezone. For an existing phone, first follow
[Shared demo calendar](../demo_calendar/README.md) to merge the former LOCAL calendars.

At each run, let `anchor` be the next whole minute:

- **MobileClaw demo meeting**: anchor − 10 minutes → anchor + 20 minutes.
- **MobileClaw demo review**: anchor + 50 minutes → anchor + 80 minutes.
- Expected callback slot: the first complete 15-minute gap after the meeting ends,
  checking all shared-calendar instances over the next seven days. With no additional
  conflict this is anchor + 20 minutes → anchor + 35 minutes; general fixtures and
  manually added events can move it later.

The test selects the shared calendar and leaves it selected for the next scenario.
The fixture calendar/contact remain available for another rehearsal. TaskBoard has no
structured deadline field; the proposed callback slot is kept in the task note.
Existing board tasks are preserved. By default, an existing Morgan callback refuses
another approved run. The explicit filming option below permits one new dated fixture slot.
Resetting TaskBoard deletes its existing tasks; use a dedicated demo board.

## Network and device requirements

The driver preserves Wi-Fi, cellular and Private DNS settings. Its existing device
preflight requires Wi-Fi enabled and the second subscription's `mobile_data2` setting
to be `0`. Other subscription layouts may need a
separate driver adaptation; selecting a new serial does not establish device support.
Configure a working connection before running a live model case.

## Reproduction

Build and install the instrumentation APK manually. Never use connectedAndroidTest,
which uninstalls the app. The test APK contains the managed-call components; the
production APK/manifest does not acquire a ConnectionService.

```bash
python3 tools/demo_calendar.py --serial SERIAL --merge
bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Offline: simulate the call and verify actual source/readers; no model or task write.
python3 tools/demo_callback/run_rehearsal.py \
  --serial SERIAL --run-id CALLBACK-EXAMPLE-A --output /tmp/callback-offline

# Live: also approve one validated local TaskBoard write. Requires no existing Morgan task.
python3 tools/demo_callback/run_rehearsal.py \
  --serial SERIAL --run-id CALLBACK-EXAMPLE-B --output /tmp/callback-live --live
```

Prerequisites: assistant already paused, notification access, Shizuku running and granted,
TaskBoard installed/launched. Use the same explicit serial for every step. Missing source/context,
wrong slot/date/timezone, extra actions, missing actual contact/calendar tool calls,
changed board before approval or changed old tasks fail the run. Exported report and
trace are private test outputs copied to the chosen host directory; screenshot PNG
signatures are checked. Shell exit code alone is insufficient: the driver requires
`OK (1 test)`.

For a repeated live-model check without adding another task, use
`--live --proposal-only`; this verifies the pending confirmation and then declines it.
The driver uses `adb` from PATH; pass `--adb /path/to/adb` when needed.

## Source implementation and limits

The test account is managed, not self-managed: Android's normal Dialer owns the call
UI and missed notification. It is registered and enabled only for the probe, then
unregistered in cleanup. The default SIM/dialer are never changed. The fixed reserved
number cannot dial out; the fixture explicitly rejects outgoing connections.
Android documents the register/enable requirement and system binding in
[`TelecomManager.addNewIncomingCall`](https://developer.android.com/reference/android/telecom/TelecomManager#addNewIncomingCall(android.telecom.PhoneAccountHandle,%20android.os.Bundle)).

The isolated test APK has another UID and runs its receiver/service in its own
process. These components use Java because target-app Kotlin runtime classes are not
available in that separate test process. The instrumentation helpers use Kotlin in
the target process. The receiver accepts only the explicit fixture action, run ID,
fixed number and register/inject/unregister stages.

Carrier-delivered calls, autonomous background suggestions and repeated-run
reliability need separate validation. Fixture contacts, calendar events, native call
history, rehearsal chats and an approved TaskBoard task remain after the run.

## Confirmation semantics

AppFunctions returns an application-owned typed receipt: AwaitingConfirmation or
NotExecuted. A deferred/rejected call ends the model loop with that status; the client
does not ask the model to summarize a write that has not happened. Model text is buffered
for catalogs containing this tool, including text streamed before tool-call fragments,
so an early "I've added" cannot flash on screen or reach the saved assistant message.
This means foreground chat with AppFunctions available displays prose after the response
state is known rather than token by token. Read-only catalogs, including background
follow-up, retain normal streaming.

Actual confirmation callbacks publish **Action result:** only after the executor returns.
Foreground, overlay and notification confirmations share this path. An error
result is reported as a failure. Declining explicitly reports
Not executed. Exceptions become failures; cancellation/reset cannot publish old results.
Platform failure or unknown output is not a successful AppFunctions audit merely because
the shell exited zero. A structured return establishes platform completion; a callee's
returned refusal remains a returned refusal, not a blanket business-success statement.

The real privileged bridge success/failure check is separately opt-in. It sets the
current theme to itself and approves a nonexistent function to inspect the failure:

```bash
adb -s SERIAL shell am instrument -w -r \
  -e class edu.pitt.isl.mobileclaw.notiftest.demo.AppFunctionConfirmationRunner \
  -e confirmationRealBridge true -e confirmationRunId CONFIRM-UNIQUE-ID \
  edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner
```

## Recording options

Direct instrumentation accepts `-e callbackRecordingHoldMs 7000` (default 0, range
0–8000). It holds the real notification, confirmation, result and TaskBoard screens;
`phaseTimings` and `startedWallMs` identify the recorded phases. Only an explicitly
authorized new fictional task should also use `-e callbackAdditionalDemoTask true`.
That flag requires live mode, approval enabled and a positive filming hold. It accepts
existing Morgan tasks only when their notes match the reserved number and missed-call
fixture, and refuses the same canonical date/start-time string. It is a narrow fixture
guard, not general semantic deduplication. Old-task equality before/after and exactly
one validated new task remain required. Default runs retain the duplicate refusal.

`-e callbackRecordingHome true` requires a positive `callbackRecordingHoldMs`. It
adds Home before the incoming call and a short Home hold after it becomes missed.
For this filming mode only, the source dismisses previous native non-summary cards
whose title is exactly `Missed call` and text exactly `Morgan Demo`. This keeps old
fictional cards from grouping the new one. No call-log rows or unrelated cards are
deleted. The default source probe leaves existing cards alone.
