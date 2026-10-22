# Demo setup

The [poster](media/poster.pdf) and [125-second video](https://github.com/pittisl/MobileClaw/raw/refs/heads/main/docs/media/demo.mp4) accompany the
[MobiCom 2026 demo paper](https://doi.org/10.1145/3795866.3848463).
These walkthroughs provide starting points for trying the system yourself.

Start with [installation](install.md) and the [quick start](quickstart.md), or
[build from source](building.md). We recommend Android 16+; Android 8.0+ is the
minimum supported version for MobileClaw. The TaskBoard AppFunctions example
requires Android 16+. Use an explicitly selected phone
(`adb -s SERIAL`) for commands below. The examples use synthetic inputs; model
responses may vary. The recorded demonstration uses a cloud reasoning model.
Connecting a local model server exercises the same client interfaces, but its
task quality must be evaluated separately.

### Demonstration capabilities

Background follow-ups use `search_files` to find local images and present actual
results as candidate cards, as shown in the poster. The cards retain the notification
context in its conversation and open the foreground preview/selection flow.
An incoming request does not select a photo or authorize sending. The user must preview a candidate, tap **Use this photo**,
explicitly request sharing, and confirm the image and recipient.

The standard APK omits optional MobileCLIP weights. The walkthrough below uses a
known filename to exercise the full candidate/selection path with the standard
search routes. The poster's visual/date query depends on the indexed content and
capture metadata available in its prepared configuration.

## 1. Chat and notification actions

Install and open MobileClaw, configure your model in **Settings > Model service**, and grant
notification-listener access. Keep Full Automation Mode off to show the normal
confirmation path.

Use the app's **Test** menu to post a sample notification, then ask in chat what
needs attention. The actionable email and confirm-request samples have real
notification buttons. Approving a proposed reply or button action lets you observe
the resulting notification and the action-history entry.

Check the status strip if no background result arrives. Notification access, the
pause state and model configuration each affect whether background triage runs.
Not every notification qualifies for a contextual follow-up. At most one follow-up
starts per source app in a five-minute window; clearing the chat does not reset
this cooldown. A second conversation in the same messaging app shares that limit.

## 2. Local documents and calendar

The [sample corpus](../tools/demo_preload/README.md) contains fictional documents
and attributed image sources. For a small document/OCR demonstration without
downloading the photo collection:

```bash
bash tools/demo_preload.sh SERIAL --photos skip
```

Grant shared-file access in MobileClaw and use **Settings → Rebuild search index
now**. Wait for indexing to complete, then ask a concrete question such as:

> Find the Cedar House Austin hotel confirmation and tell me the booking dates.

The corpus includes both text PDFs and scanned content. Search returns bounded
excerpts, so a matching document does not guarantee every page's details appear in
one tool response. CLIP weights are optional for this document/OCR example.

For calendar demonstrations, follow the
[shared demo calendar guide](../tools/demo_calendar/README.md). Select the
**MobileClaw demo** calendar in Settings and verify the actual fixture dates before
asking about availability. Some documents use fixed October 2026 dates; other
seeders use dates relative to rehearsal time. A query for today will not find an
event placed on a different date.

## 3. Structured cross-app execution with TaskBoard

This example requires an Android 16+ device with both APKs installed. Start Shizuku
and authorize MobileClaw, then launch TaskBoard once so the platform can discover
its functions. See the [AppFunctions walkthrough](../tools/README-taskboard-demo.md)
for the lower-level call sequence.

Ask MobileClaw:

> In TaskBoard, add a task called buy cat food with the note salmon flavour,
> then make the TaskBoard theme purple.

Review and approve the proposed function calls. Open TaskBoard and check the new
task and header color; also check MobileClaw's reported function results and action
history. A proposal saying it is waiting for confirmation is not evidence that the
operation has executed. These calls still require approval in Full Automation Mode.

TaskBoard is a small demo app included in this repository. The example demonstrates
execution through a cooperating app's declared functions. Each additional target
app must expose compatible functions before MobileClaw can call them.

## 4. Photo request to candidate cards

Use an Android phone with a configured model, notification access and shared-file
access. Keep **Context follow-up** on, background assistance running and **Full
Automation Mode** off. This walkthrough stops at the image/recipient confirmation
without sending an MMS.

1. Install the existing attributed photo corpus on the selected phone:

   ```bash
   bash tools/demo_preload.sh SERIAL --photos download
   ```

   The [photo manifest](../tools/demo_preload/PHOTOS.tsv) includes a husky image
   named `IMG_20260910_101122.jpg`. The command copies fixtures and registers them
   with Android; it does not grant MobileClaw permissions or rebuild its index.
2. In MobileClaw, enable **Settings > File search > Index photos and PDFs by
   content**, choose **Rebuild search index now**, and wait for indexing to finish.
   Filename lookup is available without MobileCLIP. Image labels/OCR need content
   indexing; arbitrary visual descriptions are not guaranteed to match.
3. Select the MobileClaw conversation that should receive the result. From a test
   phone or a messaging account you control, send a fresh message that produces
   a notification on this phone, for example:

   > Could you send me IMG_20260910_101122.jpg, the husky photo?

   Let background assistance handle the notification. A qualifying follow-up can
   search and show real candidate cards without a foreground search request.
   Allow at least five minutes since the previous follow-up from that same app.
   Triage admission, model decisions and an already-running follow-up can still
   prevent a new suggestion; this is not an every-notification trigger.
4. Open the candidate in MobileClaw and inspect it. Tap **Use this photo**. The
   result remains in the conversation selected when the notification arrived,
   even if you switch chats while the model works. Return to that conversation
   for selection and sharing. Clearing or deleting it invalidates an unfinished
   result; a selection from another conversation is not reused.
5. Explicitly ask to share the selected photo with a test contact or phone number
   you supply. Name-based lookup requires Contacts permission and an unambiguous
   number. The current sharing tool prepares an MMS and requires SMS permission,
   a usable SIM/subscription and carrier MMS support; it does not reply with a
   photo through arbitrary messaging apps.
6. Review the outgoing image and exact recipient in **Send photo?**. For this
   walkthrough, choose **Not now**. Sending requires a separate **Send photo**
   approval even in Full Automation Mode. Reaching this confirmation demonstrates
   preparation, not carrier transmission or recipient delivery.

If no candidate appears, check notification admission and cooldown, file access,
index completion and whether the requested file exists. Candidate cards come only
from actual search hits. An empty search or missing permission can produce an
explanation instead of cards; no photo is selected or sent by the follow-up.

To exercise capture-date filtering, use an image with known EXIF/MediaStore
capture metadata and request its actual date. The
[dated-fixture generator](../tools/demo_photo/prepare_photos.py) can create
controlled copies of the corpus with explicitly synthetic capture dates.
`yesterday` resolves in the phone's current timezone; file modification time or a
date in the filename is not a substitute for capture metadata. Unknown capture
dates are excluded from date-filtered searches. With the standard APK, content
matching relies on indexed words, OCR and image labels. MobileCLIP similarity
requires the optional build assets and still does not guarantee a match.

## Optional: show a debug build's execution trace on a laptop

This live-view walkthrough requires a separately built **debug APK**, using the
[source-build instructions](building.md#build-and-install-a-development-apk).
The signed release APK does not support the `--enable-trace` setup below:
[`TraceReceiver`](../app/src/main/java/edu/pitt/isl/mobileclaw/notiftest/TraceReceiver.kt)
ignores external trace-control requests outside debug builds, and the
[viewer](../tools/trace_viewer/trace_viewer.py) verifies the setting through
Android's debug-only `run-as` access.

The viewer also requires Python 3.9+ and `adb`; its Python code uses only the
standard library. With the debug APK installed and the phone connected:

```bash
python3 tools/trace_viewer/trace_viewer.py --serial SERIAL --enable-trace --open
```

It displays the app's trace at `http://127.0.0.1:8765/`. On Windows use:

```powershell
tools\trace_viewer\start-trace.cmd -Serial SERIAL
```

See the [trace-viewer guide](../tools/trace_viewer/README.md) and
[Windows guide](../tools/trace_viewer/README-windows.md). Traces can contain task
content after the app's pattern-based redaction, so use the synthetic fixtures for
a public demonstration. Stop the viewer with Ctrl-C and disable trace logging in
the app when finished.

## What to verify

For an action example, check the source notification or target app after approval
and inspect Action history. For document search, compare the retrieved facts with
the synthetic source document. For calendar examples, inspect the selected
calendar and the actual event dates. A model's answer or a waiting-for-confirmation
message alone does not verify an external action.

The standard APK supports the text, OCR and image-label routes used by the
document example. Image/text similarity requires the
[optional MobileCLIP assets](building.md#optional-image-similarity). The recorded
demo illustrates a specific prepared configuration, rather than a promise that
every model, device and optional capability behaves identically.
