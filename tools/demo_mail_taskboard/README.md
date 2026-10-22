# Simulated email notification → TaskBoard AppFunctions

This Android 16 rehearsal uses an explicitly labelled **synthetic email-category
notification**, posted by test instrumentation under MobileClaw's own package. It
exercises production notification capture, a live model, visible confirmation, the
Shizuku bridge, Android AppFunctions and a persisted TaskBoard task. SMTP/IMAP/Gmail
delivery and mail-client notification rendering require separate validation.

## Fixture and visitor interaction

`demo-preparation.eml` is a fictional, unsent source fixture using example.org addresses.
The runner posts its matching subject and compact body:

> **Demo preparation for October 28**
>
> Bring a USB-C HDMI adapter to the booth by Oct 28, 9 AM. Alex handles the poster; no poster task for you.

The foreground request supplies no task title, deadline or assignee answer:

> Read the latest demo preparation email notification and add only my action items to TaskBoard. Include the deadline and email subject as the source in the notes. Ask me to confirm before adding anything.

The captured notification is available in the production foreground context. A separate
`read_notifications` call is optional. An expected action has this form:

```json
{
  "package": "edu.pitt.isl.mobileclaw.taskboard",
  "function_id": "edu.pitt.isl.mobileclaw.taskboard.TaskBoardFunctions#addTask",
  "parameters": {
    "title": "Bring a USB-C HDMI adapter to the booth",
    "note": "Deadline: Oct 28, 9 AM. Source: Demo preparation for October 28."
  }
}
```

The runner verifies that this is the **only** pending action, validates title/deadline/source,
checks that the actual board has not changed, and saves the visible confirmation. It then
clicks that dialog's **Do it**, exercising the production confirmation callback rather than
executing the function itself. This local dummy-board write is intentional; no message is
sent. The runner verifies the returned task and its persisted fields, preserves existing
tasks, and rejects a task for Alex's poster work.

AppFunctions calls run between two apps **on the same phone**. A neighbouring phone cannot
host this callee without a separate remote transport, which this project does not implement.

## Scope and side effects

This is an explicit foreground workflow. During the synthetic case, background
follow-up is disabled and triage is paused/drained after capture. The runner checks
exactly one successful AppFunctions write and zero outbound-message/calendar writes.
Read-only `listTasks` calls used by the preflight are test infrastructure.

The source notification is cancelled and pending approvals are revoked during cleanup.
The new TaskBoard task and rehearsal chat remain. Configured Shizuku access remains
available. A successful fixture run demonstrates feasibility, not general reliability.

## Reproduction

Prerequisites: same Android 16 phone, both apps installed, TaskBoard launched
once, Shizuku running and MobileClaw authorized, notification access/POST_NOTIFICATIONS,
READ_CALENDAR, and **MobileClaw demo** (`LOCAL / MobileClawDemo`) selected under **Calendar
to read**. Preflight validates the owned account/name/id, not a hard-coded calendar id,
and checks the schedule fixtures without requiring the entire calendar to contain only
those events. Follow [Shared demo calendar](../demo_calendar/README.md) once to merge
the old LOCAL demo calendars; no source change is needed between scenarios.

Build and install manually; do not use connectedAndroidTest because it uninstalls apps:

```bash
bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Offline only: no model calls or task writes.
python3 tools/demo_mail_taskboard/run_rehearsal.py \
  --serial SERIAL --run-id MAILBOARD-EXAMPLE --output /tmp/mailboard-preflight

# Live: confirms ONE validated local TaskBoard addTask using the current network.
python3 tools/demo_mail_taskboard/run_rehearsal.py \
  --serial SERIAL --run-id MAILBOARD-EXAMPLE --output /tmp/mailboard-live --live
```

The driver requires `--serial`, uses `adb` from PATH, and supports `--adb /path/to/adb`.
It preserves the current network by default. `--cellular` explicitly enables a temporary
cellular interval and restores Wi-Fi/data afterward; use it only when appropriate for
the selected test device and plan. DNS, timezone and device clock are unchanged. The
driver checks `OK (1 test)`, exports reports/trace/valid PNGs and records network state.
Keep it running to completion.

The live test refuses a board already containing an HDMI task. Use TaskBoard's **Reset
board** only on a dedicated board whose existing tasks can be discarded. MobileClaw's
**Reset demo** does not reset another app's data, and marking the task done does not
bypass the duplicate guard.

## Real Gmail mode and limits

The driver also supports an already-active individual Gmail notification. A mailbox
receipt alone is insufficient: the native notification must actually exist.
Treat these probes as validation tools: receipt and notification capture alone do
not establish end-to-end support for real email.

## Read-only receipt and capture probes

`GmailNotificationProbe` reads only the authorized listener's **actual active Gmail
notifications** while the assistant remains paused. It requires a unique `MCMAIL-…`
marker, rejects group summaries and makes no model calls. It waits up to 90 seconds;
these missing-notification outcomes are failures, never silently skipped successes.

```bash
adb -s SERIAL shell am instrument -w -r \
  -e class edu.pitt.isl.mobileclaw.notiftest.demo.GmailNotificationProbe \
  -e gmailReceiptProbe true -e gmailMarker MCMAIL-EXAMPLE \
  edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner
```

The runner/driver also supports a real Gmail mode. An exact received body is used
**only as an input-isolation check**, never supplied as the model's answer in the user
prompt. A fresh paused process runs the production listener's resume/reseed, then keeps
only the one matching non-summary captured notification. It rejects missing/incomplete
or multi-mail content before any model turn. This explicit foreground path is not a
verified autonomous background triage flow.

Use `--capture-only` to inspect actual production foreground rendering (120 characters)
and the real `read_notifications` result (160 characters), verify complete content/source,
and check TaskBoard unchanged. It never submits a model request or approves an action:

```bash
python3 tools/demo_mail_taskboard/run_rehearsal.py \
  --serial SERIAL --run-id MAILBOARD-GMAIL-EXAMPLE --output /tmp/mailboard-capture \
  --gmail-marker MCMAIL-EXAMPLE \
  --gmail-expected-body 'Bring a USB-C HDMI adapter by Oct 28, 9AM. Alex handles the poster; no poster task for you.' \
  --capture-only
```

When a real individual notification exists and its exact body has been checked,
`--live` can replace `--capture-only` to exercise visible confirmation and one local
TaskBoard write. The runner uses a new empty chat, preserves existing tasks and refuses
duplicate source markers. Normal redaction can change a date-like subject marker, so
the raw subject is not promised verbatim in every rendering.

Current notification previews are bounded: foreground rendering uses 120 characters
and `read_notifications` uses 160. Long bodies, grouped notifications, threads and
attachments require additional work. There is no direct whole-mailbox/thread retrieval
tool. A synthetic notification must remain labelled synthetic when reporting a result.
