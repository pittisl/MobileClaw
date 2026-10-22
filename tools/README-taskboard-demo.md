# MobileClaw driving TaskBoard through AppFunctions

TaskBoard (`demotarget/`, applicationId `edu.pitt.isl.mobileclaw.taskboard`) is a separate
demo app: a persistent task list, a coloured header, a visible function-call log and six
functions exposed through Android 16 AppFunctions. Both apps run on the same phone.

MobileClaw calls TaskBoard through `call_app_function` → `ShellGate.modify` → Shizuku
(uid 2000) → `cmd app_function execute-app-function`. Every invocation requires user
confirmation, including when Full Automation is enabled. The platform sees
`com.android.shell` as the caller. This demonstrates structured local IPC, not a remote
transport to another phone.

## Setup

Install both apps on an Android 16 phone. Use one explicit adb serial throughout:

```bash
bash gradlew :app:assembleDebug :demotarget:assembleDebug
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r demotarget/build/outputs/apk/debug/demotarget-debug.apk
adb -s SERIAL shell pm grant edu.pitt.isl.mobileclaw.taskboard android.permission.POST_NOTIFICATIONS
```

Install and start Shizuku using its documented setup for the phone, then grant MobileClaw
access in **Settings → Privileged shell access**. The server must be restarted after a
reboot. The tested ARM64 setup can start the helper from the installed Shizuku APK:

```bash
LIB=$(adb -s SERIAL shell pm path moe.shizuku.privileged.api | sed 's|package:||; s|/base.apk||' | tr -d '\r')
adb -s SERIAL shell "$LIB/lib/arm64/libshizuku.so"
```

This path depends on the installed Shizuku package and device ABI. Confirm the actual
service connection; exit zero from a stale startup script is insufficient.

**Launch TaskBoard once after installation.** Some Android 16 devices do not index its
functions until first launch. Verify the platform index before involving the model:

```bash
adb -s SERIAL shell "cmd app_function execute-app-function --package edu.pitt.isl.mobileclaw.taskboard \
  --function 'edu.pitt.isl.mobileclaw.taskboard.TaskBoardFunctions#getBoard' --parameters '{}'"
```

Enable the optional [trace viewer](trace_viewer/README.md) to inspect tool calls and
results. The [mail-notification scenario](demo_mail_taskboard/README.md) and
[missed-call scenario](demo_callback/README.md) build on this setup.

## Demonstration

Ask MobileClaw:

> In TaskBoard, add a task called buy cat food with the note salmon flavour, then make the TaskBoard theme purple.

The model can propose two calls:

```json
{"package":"edu.pitt.isl.mobileclaw.taskboard","function_id":"edu.pitt.isl.mobileclaw.taskboard.TaskBoardFunctions#addTask","parameters":{"title":"buy cat food","note":"salmon flavour"}}
{"package":"edu.pitt.isl.mobileclaw.taskboard","function_id":"edu.pitt.isl.mobileclaw.taskboard.TaskBoardFunctions#setThemeColor","parameters":{"color":"purple"}}
```

The application shows a pending confirmation status. A proposal has not executed yet.
Approve each desired action with **Do it**; two proposals can produce two dialogs in
unspecified order. The bridge passes an argument vector, validates its shape again
inside the uid-2000 process, and invokes the platform command. TaskBoard writes the task
in its own process and returns structured data. Its header and visible log show the
confirmed result.

After confirmation, ask:

> Did both work? If so, post a notification from TaskBoard saying the board is ready.

Confirmed action results are available to the model on the next turn. A further
`postNotification` call requires its own approval. With notification access enabled,
MobileClaw can capture that TaskBoard notification through the background triage path.

## Functions and limitations

| Function | Parameters | Effect |
|---|---|---|
| `addTask` | `title`, `note` | Add a task and return its id and content |
| `completeTask` | integer `id` | Complete a task, or return a reason it cannot |
| `listTasks` | none | Read tasks with their ids |
| `setThemeColor` | `color` | Change the header colour |
| `postNotification` | `title`, `text` | Post a native TaskBoard notification |
| `getBoard` | none | Read theme and task counts |

Each function id is prefixed with
`edu.pitt.isl.mobileclaw.taskboard.TaskBoardFunctions#`. Send every declared parameter,
using `""` for optional text. The bundled catalog also describes the separately installed
[ChatApp sample](README-chatapp-callee.md).

- A deferred call returns a typed pending receipt. The model is not asked to report an
  unexecuted write as completed. The real confirmation callback reports the executor's
  result; an error, refusal or unknown response does not become a blanket success.
- A confirmed result reaches the model's next request. Ask a concrete follow-up about
  that result when continuing the demonstration.
- Callee-thrown exceptions can lose detail across the platform boundary. TaskBoard
  returns expected refusals as text where possible. Inspect the returned value and board.
- AppFunctions `alpha08` does not expose the calling package in `AppFunctionContext`.
- **Reset board** resets TaskBoard data and theme. Use it only on a disposable demo board;
  MobileClaw's **Reset demo** does not reset another app's data.

## Implementation

`CallAppFunctionTool.kt` builds the proposal and command vector; `ShellCommandPolicy`
checks the command; `ShellGate` handles confirmation; `AppFunctionResult.kt` renders the
platform return. The callee is implemented in `demotarget` by `TaskBoardFunctions.kt`,
`Board.kt`, `Notifier.kt` and its activity. Generated AppFunctions metadata is under
`demotarget/build/generated/ksp/debug/resources/assets/` after a build.
