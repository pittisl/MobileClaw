# Data, permissions and user control

MobileClaw keeps its working state on the phone and sends model requests to the
service you select. A cloud service can receive notification context, conversation
text, memories and retrieved tool results needed for a turn. Selecting an
on-device server keeps those model requests on loopback; tools with their own
online dependencies can still communicate separately.

## What is stored

| Data | Storage and retention |
|---|---|
| Captured notifications | Bounded in-memory store, up to 500 entries |
| Chat messages | App-private SQLite; up to 90 days and 5,000 persisted rows |
| Action audit trail | App-private SQLite; up to 90 days and 5,000 rows |
| Triage decisions | App-private SQLite; up to 30 days and 20,000 rows |
| Memories | App-private SQLite; no age expiry for active memories; reviewable and deletable |
| Local file index | On-device AppSearch index of permitted shared-storage content |
| Model service settings and API keys | Encrypted file in app-private, non-backup storage |

The database is excluded from Android cloud backup and device transfer, including
its journal sidecar files. It is not an application-encrypted database. The
user-initiated export is a plain SQLite file that can contain sensitive text.
Model credentials are stored separately and are not included in that export.

Pattern-based redaction applies to selected notification/decision text and the
supplied-text field of action records. It does not cover all stored data or
recognize every possible personal detail. Chat messages, conversation titles,
other action-record fields and user-stated memories can retain sensitive original
text. Review the contents before sharing an export.

## What can leave the device

Model requests can include captured notification context, selected conversation
history, injected memories and tool outputs. When image input is enabled, selected
notification images can also be sent to a vision-capable service. Local indexing
does not imply that subsequently retrieved content remains local: the agent can
include excerpts in its next model request.

The bundled ML Kit image-label and OCR models run on the phone. The dependency
also includes usage telemetry; local image processing is not a promise of zero
network traffic from every dependency. Optional MobileCLIP similarity runs through
ONNX Runtime on the phone.

Actions such as SMS/photo sending, notification replies and optional online
integrations have their own destinations. Review the proposed recipient and
content before approving an outward action. A transport result, such as an MMS
being submitted, is distinct from the recipient receiving it.

**Test connection** is separate from normal agent operation. It sends fixed
synthetic examples and a dummy tool schema to the selected model service, without
device context or actual tool execution.

## Confirmation and permissions

Outward actions require confirmation by default. A proposal appears in the app,
an allowed overlay, or a confirmation notification. If no confirmation surface
can show it, the action is dropped and recorded. **Full Automation Mode** allows
eligible actions to run without a prompt. Privileged modifications, AppFunctions
calls and selected-photo sending still require approval.

Android permissions determine what the app can access. Notification access,
contacts, SMS, calendar, shared files, usage access and display-over-other-apps
access serve different capabilities. Grant the capabilities needed for the tasks
you want to try. Shizuku additionally enables the current privileged helper;
its command allowlist and path checks still apply to an approved operation.

Pause stops background notification capture, triage and the daily memory pass.
The pause state survives restarts. Foreground chat remains available and can send
requests when you ask a question. Resuming captures existing notifications as
context without replaying a backlog of triage requests.

## Review, delete and export

- **Settings > Data > Action history** shows recorded action and proposal
  outcomes, including successes, failures and some rejections or expirations.
  Declining an in-app confirmation can be recorded only in the conversation;
  Action history is not a complete record of every proposal outcome.
- **Settings > Memory > What the agent remembers** lets you inspect, pin and
  delete memories. User-stated facts and inferred information are identified
  separately.
- **Forget everything** clears memories. **Clear agent history** clears the
  action and decision records; it does not clear memories.
- **Clear chat** clears the current conversation. Tap the conversation selector
  at the top of the chat to create, switch or delete conversations.
- **Settings > Data > Export data** writes an unencrypted database to the
  destination you select. Keep that file private if it contains personal data.

The optional reasoning trace writes task information to Android's system log
after pattern-based redaction. Use synthetic fixtures when displaying or sharing
traces, and turn trace logging off after the demonstration.
Other diagnostic logs can include tool arguments, short result excerpts and
action records. Review system logs before sharing them.

For credential encryption, first-run developer seeding and the limits of
protection on a rooted or instrumented device, see
[model-service configuration](model-configuration.md#credential-storage-and-development-builds).
