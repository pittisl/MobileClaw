# Demo reset

On the main screen, choose **⋮ → Reset demo → Reset**. Wait for the completion toast;
if a turn is still stopping, a non-cancelable waiting dialog remains visible.
Canceling the confirmation leaves the demonstration untouched.

Reset drains foreground chat and background notification reasoning, revokes outstanding
approvals and queued confirmation callbacks, clears the chosen chat and captured events,
and clears follow-up cooldown and triage dedup/context. An old listener's late result
cannot publish into the new round, even if the listener disconnected while resetting.
New posts on a reused notification key are admitted; posts already in the shade are ignored.

Files and their index, calendar, contacts, saved memory, other chats, settings and audit
history remain. A message already sent or an action already admitted cannot be undone.
The laptop trace viewer, TaskBoard and changes made to fixtures are reset separately.
Ordinary **Clear chat** still does not reopen the five-minute follow-up window.
Notification baselines and triage context isolation are process-local: after restarting or
reinstalling, open MobileClaw and use **Reset demo** before admitting the next visitor.

## Verify reset behavior

Build the debug app and instrumentation APK, install both with `adb install -r` (do not
use `connectedDebugAndroidTest`, which uninstalls them afterward), and unlock the phone.
Select the explicit serial for every operation:

```sh
adb -s SERIAL shell am instrument -w -r -e listenerReset true \
  -e class edu.pitt.isl.mobileclaw.notiftest.DemoResetTest,edu.pitt.isl.mobileclaw.notiftest.NotifConfirmTest \
  edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner
```

The opt-in `listenerReset` setup grants notification-listener access and pauses background
reasoning while each reset case runs, restoring the original pause flag afterward. It
uses isolated chats, scripted producers and fake approvals; it sends no SMS/call or live
LLM request. Notification-confirmation tests grant POST_NOTIFICATIONS. Run this only on
an authorized demo phone: UI tests open the app, and confirmation tests leave audit rows.
