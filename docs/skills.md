# Skills and tools

MobileClaw registers **11 built-in Skills containing 24 tools**. A Skill groups
related capabilities; the model calls the individual tool names listed below.
Registration does not grant Android permissions or configure external services.

We recommend **Android 16+**; **Android 8.0+** is the minimum supported version.
The AppFunctions skill requires Android 16+. Start with [installation](install.md)
and [quick start](quickstart.md), and configure a compatible service in
**Settings > Model service**. Tool results can become context for that service;
see [data and permissions](privacy.md).

## Catalog

| Skill ID | Capability | Tools |
|---|---|---:|
| [`notifications`](#notifications) | Read and act on captured notifications | 4 |
| [`messaging`](#messaging) | Find contacts, compose messages, send SMS or a selected photo | 4 |
| [`calendar`](#calendar) | Read calendar events and add an event | 2 |
| [`music`](#music) | Inspect and control an active media session | 1 |
| [`performance`](#performance) | Inspect memory use and stop or restrict an app | 2 |
| [`filesystem`](#filesystem) | Read and modify permitted shared-storage paths | 1 |
| [`context`](#context) | Read device time and recent app usage | 2 |
| [`search`](#search) | Search indexed local documents and images | 1 |
| [`memory`](#memory) | Store, retrieve and delete local memories | 3 |
| [`notion`](#notion) | Search, read and edit accessible Notion pages; build from source with your own Notion token | 3 |
| [`appfunctions`](#appfunctions) | Invoke a cooperating app's declared Android functions | 1 |
| **Total** | | **24** |

## How confirmation works

- **No separate confirmation:** the tool runs when called, after its access and
  input checks. This includes reads, music control, and local memory operations.
- **Confirm by default:** the tool stages an action for approval. Turning on
  **Full Automation Mode** allows these eligible actions to execute automatically.
- **Always confirm:** approval remains required with Full Automation Mode on.
  This applies to privileged modifications, AppFunctions calls, and sending a
  selected photo.
- **Send in the destination app:** the tool opens a prefilled composer; the user
  reviews and sends there. Opening the composer has no MobileClaw confirmation.

Android permission grants and action approvals are separate. A permission grant
allows access to a capability; an action approval authorizes a particular proposed
operation. Confirmation normally appears in the foreground activity, an allowed
overlay, or a notification. Sending a selected photo requires the foreground
conversation and its image/recipient review. A prepared proposal does not mean an
operation has completed; check its result and **Settings > Data > Action history**.

For tools requiring privileged shell access, start
[Shizuku](https://shizuku.rikka.app/) and authorize MobileClaw. The helper must be
running and connected. Ordinary chat and most other skills do not require it.

## Notifications

Enable **Notification access** so MobileClaw's listener can capture notifications.
The read tool queries recent notification posts captured by the listener; results
can include older versions or notifications that have since been removed. Pausing
background assistance stops new capture. Action tools require a connected listener;
replies and button presses also require a matching notification that is still active.
Replies need an inline-reply action; button presses depend on the
actions actually exposed by the source app. Background actions that open another
app may also need **Allow app-open** / display-over-other-apps access.

| Tool | Purpose | Confirmation |
|---|---|---|
| `read_notifications` | Read recent captured notification posts, optionally filtered by app or messaging category | No separate confirmation |
| `dismiss_notification` | Clear a matching notification | Confirm by default |
| `reply_notification` | Send text through a notification's inline reply action | Confirm by default |
| `click_notification_action` | Press an exposed notification button, with text when that action accepts it | Confirm by default |

## Messaging

Contact lookup requires **Contacts** (`READ_CONTACTS`). Direct SMS and photo MMS
require **SMS** (`SEND_SMS`) and a device/SIM/carrier that supports the requested
transport. Resolving a photo recipient by contact name also requires Contacts;
using an explicit phone number does not. The composer tool needs an installed app
that can handle the chosen channel and does not require MobileClaw's SMS permission.

| Tool | Purpose | Confirmation |
|---|---|---|
| `lookup_contact` | Find matching contacts and phone numbers | No separate confirmation |
| `compose_message` | Open a prefilled email, SMS, WhatsApp, Telegram or share composer | Send in the destination app |
| `send_sms` | Send text directly to a phone number through Android's SMS service | Confirm by default |
| `send_photo` | Send the photo selected in the current conversation as MMS | Always confirm |

For photo MMS, preview a local result, tap **Use this photo**, and explicitly ask
to share it in that conversation. The tool accepts a recipient and optional
caption, not a model-supplied image path. It requires the active foreground
conversation and a current selection, then shows the image and recipient for
approval. Carrier MMS support and size limits apply. See the
[photo walkthrough](demo.md#4-photo-request-to-candidate-cards).

## Calendar

Reading requires **Calendar read access** (`READ_CALENDAR`) and follows the
calendar source selected in Settings. Creating an event requires both
`READ_CALENDAR` and `WRITE_CALENDAR`, plus one selected writable calendar.
An all-calendars read selection is not a destination for a new event.

| Tool | Purpose | Confirmation |
|---|---|---|
| `read_calendar` | List events in a requested time range | No separate confirmation |
| `create_event` | Add an event to the selected writable calendar | Confirm by default |

## Music

Enable **Notification access**. A player must expose an active Android media
session; the tool uses the first session returned by Android. It cannot control
an app that exposes no media session.

| Tool | Purpose | Confirmation |
|---|---|---|
| `music_control` | Read now-playing metadata or request play, pause, next or previous | No separate confirmation, including playback changes |

## Performance

Memory inspection can run without Shizuku, but data may be limited or unavailable.
A connected Shizuku helper provides privileged process inspection, force-stop and
standby-bucket restriction. Without it, inspection can use fresh output from the
manually started [ADB memory collector](../tools/meminfo_collector.sh), then falls
back to the process data Android allows the app to read. Restriction has no
unprivileged fallback.

| Tool | Purpose | Confirmation |
|---|---|---|
| `memory_monitor` | Report up to three visible processes with the highest memory use in the available data | No separate confirmation |
| `kill_process` | Stop an app or place it in the restricted standby bucket | Always confirm for privileged force-stop/restrict; confirm by default for the unprivileged background-stop fallback |

Without the helper, `stop` only requests Android's limited background-process
termination. It is not a force-stop or a guarantee that the target stopped;
foreground/system processes and platform restrictions limit its effect, and apps
may restart. Full Automation Mode can bypass confirmation for this fallback.

## Filesystem

Shared-storage reads use either **All files access** or the connected privileged
helper. On Android 11+, All files access is a special-access setting; Android
8–10 uses `READ_EXTERNAL_STORAGE`. Modifications require the privileged helper.
All paths and operations remain subject to the filesystem and shell policies;
granting access does not provide unrestricted access to other apps' private data.

| Tool | Purpose | Confirmation |
|---|---|---|
| `file_system` | List, read or search permitted paths; write, copy, move, create directories or delete when privileged access is available | No separate confirmation for reads; always confirm for modifications |

## Context

Device time needs no additional Android permission. Recent usage requires
**App usage access** (`PACKAGE_USAGE_STATS`, granted through Android's special
access settings).

| Tool | Purpose | Confirmation |
|---|---|---|
| `get_current_time` | Read the phone's current local date, time and time zone | No separate confirmation |
| `usage_context` | Summarize recently used apps | No separate confirmation |

## Search

Grant **All files access** and build the local index using
**Settings > Rebuild search index now**. A first search can also start a bounded
initial build. Standard APKs support filename/text, OCR and image-label search.
Image-content processing depends on the relevant Settings options; image/text
similarity additionally requires the optional
[MobileCLIP source-build assets](building.md#optional-image-similarity).

| Tool | Purpose | Confirmation |
|---|---|---|
| `search_files` | Search indexed files and return local results with bounded excerpts; image searches can use available capture-date metadata | No separate confirmation |

Search does not send a file to a recipient. Selecting and sharing an image uses
the separate foreground selection and confirmation flow described above.

## Memory

These tools use MobileClaw's local memory store and require no additional Android
permission or external-service account. They have no per-operation confirmation
dialog. Memories can be reviewed and managed in Settings.

| Tool | Purpose | Confirmation |
|---|---|---|
| `recall` | Retrieve stored memories, optionally filtered | No separate confirmation |
| `remember` | Store a fact under the memory provenance and redaction rules | No separate confirmation |
| `forget` | Delete one memory by its ID | No separate confirmation |

`remember` requires an agent-turn context and is capped at five attempts per
turn. The app determines whether a stored statement matches the user's words and
which sensitive details need redaction. These write rules do not turn memory into
permission to act. The notification follow-up loop excludes `remember` and `forget`.

## Notion

**Notion search, reading and editing are implemented and require your own Notion
API token.** The distributed APK contains no Notion token and cannot use Notion;
there is currently no in-app Notion token field or sign-in flow.

To try the integration, **build from source with your own Notion
internal-integration token** and grant that integration access to the pages you
want to use. Follow the [Notion build configuration](building.md#enable-notion-in-your-own-build)
for token setup and the debug build command. Keep the resulting APK private:
it embeds your token. Public builds and all release builds omit the token.

**Settings > Model service** configures the reasoning model only. A model-service
API key does not configure Notion. The Notion tools can access only pages granted
to your integration.

| Tool | Purpose when Notion is configured | Confirmation |
|---|---|---|
| `notion_search` | Find pages accessible to the integration | No separate confirmation |
| `notion_get_page` | Retrieve page metadata and supported content blocks | No separate confirmation |
| `notion_edit_page` | Rename a page, append a paragraph or replace an existing block's text | Confirm by default |

The integration covers these page/text operations, not general Notion database
management. These operations contact Notion's service.

## AppFunctions

Requires **Android 16+**, a connected privileged helper, and an installed target
app that exposes compatible AppFunctions. The included TaskBoard provides a
reproducible target; launch it once after installation. This tool calls a specified
package/function with structured parameters. It does not enumerate arbitrary
installed apps' functions or operate apps that expose no compatible interface.

| Tool | Purpose | Confirmation |
|---|---|---|
| `call_app_function` | Invoke a cooperating app's declared function and return its result | Always confirm, including functions that only read data |

See the [TaskBoard walkthrough](demo.md#3-structured-cross-app-execution-with-taskboard)
and [architecture](architecture.md) for the execution path.

## Catalog scope

The counts above describe the full built-in catalog. A particular agent turn may
receive only a subset: notification follow-ups use a restricted read-only catalog,
while foreground chat has the broader tool set. Permission checks, configuration
and device support still apply to each invocation.

The registration source is
[`SkillRegistry`](../app/src/main/java/edu/pitt/isl/mobileclaw/notiftest/Skill.kt),
with implementations under
[`skills/`](../app/src/main/java/edu/pitt/isl/mobileclaw/notiftest/skills).
