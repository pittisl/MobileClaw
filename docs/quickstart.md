# Quick start

This walkthrough uses a synthetic notification to check the connection and action
confirmation path. Start with the [installation guide](install.md) or
[build from source](building.md). We recommend Android 16+; Android 8.0+ is the
minimum supported version.

## 1. Connect a model

Open MobileClaw and choose **Settings > Model service**. Select Gemini, OpenAI,
Local server or Custom. Enter a model ID supported by your service and the
connection details, including your own API key when required.

The service must support OpenAI-compatible `/chat/completions`, streaming text,
tool calls and structured JSON for notification triage. **Test connection** checks
these features with synthetic inputs; it does not send device context or execute
device tools. Save the service after reviewing the results.

For a model already running on the phone, select Local server and enter its
loopback Base URL, for example `http://127.0.0.1:8080/v1/`. Select **No
authentication** if the server needs no key. MobileClaw does not start the server
or download a model. See [model services](model-configuration.md) for context
budgets, image input and forwarded endpoints.

## 2. Enable notification access

Tap **Grant access**, find MobileClaw in Android's notification-access settings,
and enable it. Allow MobileClaw to display notifications when Android asks; this
provides background confirmation prompts and the pause/resume control.

Keep **Settings > Full Automation Mode** off. The status strip indicates whether
background assistance is running and identifies missing access or configuration.
Pause stops background notification capture and reasoning and remains in effect
after a restart. Foreground chat continues to answer user-initiated requests.

Notification text and selected context can reach the configured model. Read
[data and permissions](privacy.md) before enabling access on a personal device.

## 3. Try a notification action

Use the app's **Test** menu to post the actionable email sample with **Archive**,
**Mark as read** and **Reply** buttons. Then ask in chat:

> What does the sample email say? Draft a short reply confirming I received it.

If the model proposes a reply, review the confirmation before approving it. The
synthetic email updates to show the reply; it does not send a real email. Check
**Settings > Data > Action history** for the executed action or any failure.

The confirm-request sample also has real **Confirm** and **Cancel** notification
buttons. Other sample notifications exercise triage decisions; their placeholder
buttons are not equivalent to real app actions. Model wording and decisions can
vary between runs.

## 4. Add capabilities as needed

| Capability | Setup |
|---|---|
| Contacts and messaging | Grant contacts/SMS permissions when using the relevant tool; real messaging actions can send messages |
| Calendar | Grant calendar access; select one writable calendar in Settings for additions |
| Document and photo search | Grant shared-file access and rebuild the local search index |
| Open another app from a background notification action | Use **Allow app-open** to grant display-over-other-apps access |
| Privileged modifications and TaskBoard AppFunctions | Start [Shizuku](https://shizuku.rikka.app/) and authorize MobileClaw; TaskBoard also requires Android 16+ |

The [demo guide](demo.md) supplies document, calendar and TaskBoard examples.
The standard APK supports text, OCR and image-label search. MobileCLIP similarity
requires an [optional source build](building.md#optional-image-similarity).

## If the first attempt fails

- **No model response:** revisit Model service and test the saved endpoint. Confirm
  its model ID, credentials and supported API features.
- **No background triage:** check notification access, the pause control and the
  status strip. Existing notifications are captured as context without replaying a
  backlog of reasoning; post a fresh test notification.
- **No follow-up suggestion:** only selected notifications qualify, and follow-ups
  are rate-limited. Foreground chat can still read the current notification state.
- **An approved action did not happen:** inspect Action history. A proposal waiting
  for approval is not an executed action.
- **A tool lacks access:** enable the specific capability it requests, then retry
  the task. Ordinary chat does not require granting every optional permission.
