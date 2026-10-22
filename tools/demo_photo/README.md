# Photo search, selection and confirmed MMS

## Direct phone numbers

After selecting a photo, the user can ask `send that pic to +1 (202) 555-0198`.
`send_photo` accepts exactly one of `contact_name` or `phone_number`, plus an optional
`body`. A direct number does not need a saved contact or Contacts permission. It is
normalized to 7–15 digits with an optional leading `+`; extensions and multiple
recipients are rejected. Numbers in the legacy `contact_name` field also use this
direct path. Name-based lookup still requires a unique contact/number and rechecks
the contact when approved. Both paths show the exact image and recipient number in
the mandatory confirmation, even in Full Automation. Nothing is sent by preparation.

## Implementation and limits

The scenario finds photos by content and capture date, previews/selects one inside
MobileClaw, and prepares an image message with explicit image/recipient confirmation.
The automated scenario declines that confirmation; carrier transmission and recipient
receipt require separate validation.

- AppSearch schema **v5** stores capture time separately from modification time and
  triggers a rebuild of old indexes. EXIF DateTimeOriginal/OffsetTimeOriginal has
  priority, then MediaStore DATE_TAKEN. Offsetless EXIF uses the device timezone and
  records that assumption; unavailable dates remain unknown.
  AndroidX ExifInterface **1.4.2** handles explicit offsets that the API 26 platform
  parser drops. API 26 device execution remains unverified; validate EXIF/date handling on the
  intended device.
- `search_files` supports `captured_on` (yyyy-MM-dd, today, yesterday), or inclusive
  `captured_after` and exclusive `captured_before` dates in the device timezone.
  Unknown dates are excluded. All lexical, similarity, OR and kind-fallback paths
  retain the date constraint and filter ranked pages before applying the result limit.
- Actual search hits become local thumbnails and an in-app preview. Selecting a
  photo binds its session and fingerprint to the exact bytes shown in that preview.
  A later file replacement is rejected. Deselecting preserves the result cards;
  clearing/deleting a chat invalidates them. Selection is ephemeral across process
  death, and untrusted filenames are not inserted into the SYSTEM prompt.
- `send_photo` resolves a unique contact and phone number, resizes/re-encodes a JPEG
  within the carrier limits, and builds a private MMS PDU. The confirmation shows
  those outgoing image bytes and the exact recipient. It cannot auto-execute in
  Full Automation, use an overlay/notification approval, or proceed from another
  conversation. Approval rechecks the selection, source/draft fingerprints, contact,
  carrier limits and five-minute expiry before the transport boundary.

For the dog fixture below, the intended interaction is:

1. `find me my photo about the dog yesterday`
2. Select a returned photo in the actual preview.
3. `send that pic to Amy's phone`

The fixture expects three previous-day dogs and excludes the older dog and the cat.
The model should call `search_files` and `send_photo`; a separate `lookup_contact` is
optional because `send_photo` checks Contacts itself. Validate the actual prepared image
and recipient rather than a model's statement that a message was sent.

## Reproduce the complete in-app scenario

Use the authorized, awake demo phone with the assistant paused, Full Automation
off, Contacts access granted, a configured live model, and the five dated files
already indexed. The runner refuses an existing contact matching Amy, creates
only its own fictional Amy Demo contact, and removes that contact afterward.
It requires `photoDay` to equal the device's actual yesterday; regenerate and
reindex the fixtures for later dates instead of replaying stale dates.

Install matching app and test APKs. Use `am instrument` rather than
`connectedDebugAndroidTest`, which uninstalls the apps afterward.

```sh
bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w \
  -e class edu.pitt.isl.mobileclaw.notiftest.demo.PhotoScenarioDemo \
  -e photoScenario true -e photoRunId PHOTO-EXAMPLE-1 \
  -e photoFixtureDirectory /storage/emulated/0/Pictures/MobileClawPhotoDemo/EXIF-EXAMPLE \
  -e photoDay 2026-10-14 -e photoRecordingHoldMs 0 \
  edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner
```

The example dates are illustrative: replace `photoDay` and regenerate the fixture for
the selected phone's actual yesterday. Use a fresh run ID. To add readable viewing holds, set
`-e photoRecordingHoldMs 4000`: candidates, preview and confirmation each remain
visible for four seconds, adding twelve seconds total. The runner produces real
candidate/preview/confirmation screenshots, UI trees, the model trace, prepared
record and `report.json` under app-private `files/demo-photo-scenario/<runId>/`.
It never approves sending; it declines and verifies cleanup, preserves paused
state/Full Automation off, restores the previous chat and trace setting, and
checks unchanged outbound provider rows.

## EXIF fixtures

`prepare_photos.py` creates five **copies** of the already-attributed Commons
photos in `tools/demo_preload/cache`. Dates are explicitly **synthetic demo
metadata**, not the source photos' real capture dates:

| Ordinary camera filename | Picture | Synthetic capture time (New York) | Expected match for dog on Oct 14 |
|---|---|---|---|
| IMG_20261014_091500.jpg | Golden retriever | Oct 14, 09:15 | Yes |
| IMG_20261014_123000.jpg | Husky | Oct 14, 12:30 | Yes |
| IMG_20261014_174500.jpg | Border collie | Oct 14, 17:45 | Yes |
| IMG_20261013_102000.jpg | Dachshund | Oct 13, 10:20 | No: date |
| IMG_20261014_141000.jpg | Cat | Oct 14, 14:10 | No: content |

The script checks source preservation, compressed-image and decoded-pixel equality
and writes a manifest. After installation, check Android ExifInterface and MediaStore
`date_taken` against that manifest. File modification time is the copy time and must
not be treated as capture time.

Only EXIF date/offset fields are changed. Original TIFF bytes (apart from the
root directory pointer), unknown tags, artist/copyright fields, non-EXIF JPEG
segments, and image data are retained. The source cache is never overwritten.
The dated filenames contain no animal keywords, so a content-search
check cannot succeed just by matching `dog` in a filename.

Keep generated copies in a separate directory. For a later rehearsal, regenerate
with explicit dates and reindex; do not reinterpret the fixed example dates as a
moving "yesterday". Preserve source attribution from
[the corpus manifest](../demo_preload/README.md#sources-and-attribution), including
notice of the synthetic metadata change when distributing these copies.

```sh
uv run --no-project --with Pillow \
  tools/demo_photo/prepare_photos.py \
  --target-date 2026-10-14 --other-date 2026-10-13 \
  --timezone America/New_York --output /tmp/mobileclaw-photo-fixtures-new
```

Copy only the five JPEGs into the fixture directory used by `photoFixtureDirectory`.
Keep the attribution/verification manifest on the host:

```sh
adb -s SERIAL shell mkdir -p /sdcard/Pictures/MobileClawPhotoDemo/EXIF-EXAMPLE
for photo in /tmp/mobileclaw-photo-fixtures-new/IMG_*.jpg; do
  adb -s SERIAL push "$photo" /sdcard/Pictures/MobileClawPhotoDemo/EXIF-EXAMPLE/
  adb -s SERIAL shell content call --uri content://media/external/file --method scan_file \
    --arg "/sdcard/Pictures/MobileClawPhotoDemo/EXIF-EXAMPLE/$(basename "$photo")"
done
```

Rebuild MobileClaw's search index in Settings and wait for completion. Use a fresh
fixture directory for a new date; remove only your obsolete fixture copies if duplicate
search hits would otherwise remain.

## Reproduce the composer probe

Physical-phone authorization and an awake phone are required. Keep the assistant
paused and Full Automation off. Install **only** the test APK when the matching
production APK is already installed; do not use `connectedDebugAndroidTest`,
which uninstalls the apps afterward.

```sh
bash gradlew :app:assembleDebugAndroidTest
adb -s SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w \
  -e class edu.pitt.isl.mobileclaw.notiftest.demo.PhotoMmsDraftProbe \
  -e photoMmsDraft true -e photoRunId PHOTOMMS-00000001 \
  -e photoPath /storage/emulated/0/Pictures/MobileClawPhotoDemo/EXIF-EXAMPLE/IMG_20261014_091500.jpg \
  edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner
```

Use a unique run ID with 8–32 digits or hyphens after `PHOTOMMS-`;
`PHOTOMMS-00000001` above is an example. On the Android 16 AOSP Messaging
app, the prefilled-address route does not retain the image; it creates the labelled
fictional conversation needed by the recipient-picker route. Repeat with another
numeric run ID and `-e chooseRecipient true` to select that exact fictional
conversation and verify the image. The only programmatic click is that recipient
row; the harness never clicks Send.
Finishing instrumentation at the picker is insufficient: keep it running until
the receiver has consumed the FileProvider attachment and shown the composer.

Reports/screenshots are under the app's private
`files/demo-photo-mms/<runId>/`. Inspect the actual image, recipient and MMS
indicator, then remove the test attachment/text without sending.

## Native composer compatibility

The separate `PhotoMmsDraftProbe` checks Android's external Messaging composer; it is
not the production in-app MMS confirmation path. On Android 16 AOSP Messaging,
`ACTION_SEND` with an image, address and body retains the recipient/body but discards
the image. Sharing the image without `address`, then selecting the fictional recipient
inside the native picker, retains the image but does not retain the text body.

This matches AOSP's
[ShareIntentActivity redirect](https://android.googlesource.com/platform/packages/apps/Messaging/+/461a34b466cb4b13dbbc2ec6330b31e217b2ac4e/src/com/android/messaging/ui/conversationlist/ShareIntentActivity.java)
and [recipient/body handling](https://android.googlesource.com/platform/packages/apps/Messaging/+/d3b009ae55651f1e60950342468e3c37fdeb0796/src/com/android/messaging/ui/conversation/LaunchConversationActivity.java).
Other messaging apps may differ. A composer screenshot or carrier configuration does
not establish carrier delivery. Revalidate after changing the model, fixture date,
app build or target device.
