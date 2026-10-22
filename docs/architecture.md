# Architecture

MobileClaw is a Kotlin Android application using views and XML. Its application ID
is `edu.pitt.isl.mobileclaw.notiftest`. `demotarget` builds
TaskBoard as a separate package and process. See [building and testing](building.md)
for the toolchain and a code map.

## Two entry points, shared state

`NotifListener` captures notifications while `AgentRunState` is running.
`ReasoningModule` classifies each notification with the current notification
context and produces an `ActionDecision`. For selected notifications, `FollowUp`
admits one bounded tool-using turn over a read-only tool subset. A turn-local reply
draft can prepare a proposal without sending it. Notification content stays
separate from the user's own messages.

Foreground chat runs through `MainActivity`, `MainViewModel` and `OpenAiAiClient`.
It reads the same stores and can use the broader tool catalog. Pausing background
capture does not disable this user-initiated path. The listener and activity share
one app process, so singleton stores provide the shared state.

### Photo suggestions from a notification

`search_files` is part of the background read-only catalog.
`NotifListener` runs that catalog in a turn-local `PhotoTurn`, so actual image
hits can become candidate cards. `NotificationPhotoSuggestion` prepares this
review-only result as an alternative to a text-reply draft. It cannot select an
image, choose a recipient or send anything, and it clears older candidates when
a new search begins. Missing access or an empty search cannot create photo hits.

`FollowUpSession` captures the conversation when the notification arrives. The
redacted request context and result are published there even if the user switches
conversations; clearing or deleting the original conversation invalidates the
unfinished result. Incoming notification text remains third-party context, not
the user's instruction or permission to share. No previous foreground selection
is supplied to the background turn.

The foreground handoff uses the photo flow: preview, **Use this photo**,
an explicit sharing request, then image/recipient confirmation. Selection is bound
to the conversation and image bytes. The follow-up admission rules still apply,
including one turn per source app in each five-minute window; this integration
does not make every photo-related notification start a search.

```text
Notification -> capture -> triage -> selected contextual follow-up -> suggestion
                   |                                                |
                   v                                                v
              shared stores <------ foreground chat ----------> tool calls
                                                                   |
                                                                   v
                                                        action proposal / read
                                                                   |
                                                                   v
                                                       Android API or helper
```

## Model and tool loop

`AiClient.run(spec, history)` returns a flow of agent events. `OpenAiAiClient`
constructs one leading system message, selects conversation history, and streams
an OpenAI-compatible `/chat/completions` request. It assembles fragmented tool
calls, invokes registered tools, and appends their results for up to five
tool-execution rounds. A subsequent model request can produce the final answer;
further tool calls are stopped at the limit. The phone owns this loop; the
configured model endpoint performs inference.

`SkillRegistry` groups tools by capability and exposes a flat catalog through
`ToolRegistry`. A tool is a name, description, JSON Schema and suspending invocation
returning text. Current interfaces include notification actions, contacts and
messaging, calendar, shared-file search, memory, and AppFunctions.

`HistoryBudget`, `ToolOutputBudget` and `MemoryInjection` separately bound history,
tool results and injected memories. Their character-based estimates are tuned for
English; they are not hard tokenizer limits for other languages. Usage reported by
the model provider is displayed per turn.

## Execution and confirmation

`PendingAction` routes proposals to an activity dialog, overlay or confirmation
notification. The default policy requires approval. Full Automation Mode bypasses
that step only for proposals permitting it. The policy is explicit on the proposal;
privileged modifications, AppFunctions calls and photo sending opt out of the bypass.

Ordinary tools use Android APIs and granted app permissions. `FileSystemTool` can
read shared storage directly with All files access. Privileged operations pass
through `ShellGate` to a Shizuku user service running as the ADB shell identity.
`PrivilegedService` independently checks `ShellCommandPolicy` at that boundary.

The current cross-app AppFunctions path stages a confirmed argument vector for
`cmd app_function execute-app-function` through this helper. It requires Android
16 or newer and a cooperating target app. TaskBoard exposes the demo functions;
this is distinct from MobileClaw's own `ping` AppFunction.

## Storage and search

Room stores chat sessions/messages, action logs, decisions and memories in an
app-private database. Notification capture is an in-memory bounded store. Streaming
chat updates stay in memory until a turn settles; queued database writes preserve
submission order. Session-addressed slots keep a response in its original session
if the user switches conversations while it streams.

Search indexes local files with AppSearch. Bundled ML Kit models provide image
labels and OCR; optional MobileCLIP embeddings provide image/text similarity via
ONNX Runtime. Missing CLIP assets disable that route without preventing the app
from building. The standard APK omits those optional weights. These indexing steps
run on the phone. Retrieved excerpts and tool
results may subsequently be sent to the model endpoint.

The database is excluded from Android backup and device transfer. API credentials
live outside it in the encrypted configuration store. See
[data and permissions](privacy.md) for retention, data flow and user controls.

Memory provenance distinguishes user-stated facts from inferred information.
Background notification follow-ups cannot invoke the memory-write tools. These
rules limit how background content enters persistent context; they do not make
arbitrary model-generated decisions a security boundary.

## Verification

Pure logic and the SSE/tool loop have JVM tests; the latter use canned responses
from MockWebServer. Framework behavior, database migrations and on-device tool
execution have instrumented tests. Room schema files and the generated demo corpus
are test inputs and must remain in the source distribution.

`eval/ModelEvalRunner` is an explicitly enabled live-model evaluation runner,
separate from ordinary regression tests. A successful build or canned-model test
does not establish the success rate of a live endpoint on a user's device.

## Model configuration

`LlmProfile` describes one service, its authentication mode, model and input limits.
`LlmConfigRepository` publishes an immutable `LlmSnapshot` only after an atomic
write succeeds. `EncryptedLlmConfigStore` encrypts the profiles with AES-256-GCM
using an AndroidKeyStore key and stores ciphertext under `noBackupFilesDir`.
Storage failures leave the service unconfigured; explicit save/clear can recover.

Chat, notification triage/follow-up, and daily consolidation capture a configuration
snapshot for each run. A saved change affects new runs; status updates from an older
revision cannot reject the new configuration. No-auth local services omit the
Authorization header, and all model clients reject redirects. Local loopback calls
do not require Internet connectivity; tool-specific online services still do.

`ModelConnectionProbe` sends fixed synthetic inputs and a dummy tool schema. Its
streaming, tool-call, JSON and optional image results are separate. It never calls
the registered device tools or includes device context. See
[model configuration](model-configuration.md) for setup and credential boundaries.
