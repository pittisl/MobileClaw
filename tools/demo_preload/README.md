# Repeatable demo media corpus

This directory contains fictional sample documents and a manifest for 20 Wikimedia
Commons photos. Install the same corpus on each demo phone. The five PDF/PNG
assets are explicitly marked **DEMO / SAMPLE** and contain no real guest, ticket,
reservation or credential. Their facts are in the content, rather than the filenames.

## Install

From the repository root, use one exact adb serial:

```bash
bash tools/demo_preload.sh <serial>
```

The first run fetches and caches the 20 Commons photos and their source attribution.
Subsequent runs reuse that cache. For a guaranteed offline run:

```bash
bash tools/demo_preload.sh <serial> --photos cached
```

The offline mode requires all 20 photos and `cache/ATTRIBUTION.json`; it fails before
copying anything if the cache is incomplete. Copy the entire cache directory to the
other booth laptops, or choose a shared cache using `--cache <directory>`.

For the OCR acceptance check alone, or a phone that already has the Commons photos:

```bash
bash tools/demo_preload.sh <serial> --photos skip
```

This installs the six notes and five PDF/PNG assets without network access.
It leaves existing phone files in place. None of these modes deletes phone data.
They overwrite only the corpus's named files. `--list` prints all 31 destination
paths without contacting a phone. The script explicitly selects media files; it
does not install this README, scripts, source manifests or known answers.

After installation, open **MobileClaw > Settings > Rebuild search index now**.
Allow the rebuild to finish before testing. The preloader scans files into
MediaStore, but does not change app permissions or force the app's rebuild.

## PDF/PNG assets and expected facts

| File | Phone directory | Important facts |
|---|---|---|
| `hotel-confirmation.pdf` | `/sdcard/Download/MobileClawDemo/` | Cedar House Austin; LKVQ7X; Oct 25-30, 2026; USD 1,236.80; page 2: breakfast 06:30-09:30, Wi-Fi CedarDemo / MAPLEWAVE |
| `boarding-pass.png` | `/sdcard/DCIM/Camera/MobileClawDemo/` | UA1234; PIT to AUS; Oct 25; departure 09:40, boarding 09:00; gate B18; seat 12A; QXAT7P |
| `restaurant-card.png` | `/sdcard/DCIM/Camera/MobileClawDemo/` | Juniper Table; JNPR8K; Oct 27 at 18:30; 4 guests; Garden room |
| `course-handout.pdf` | `/sdcard/Download/MobileClawDemo/` | CS2610; Nov 2 at 17:00; 6 pages excluding references; Oct 29 office hours 15:00-16:00 in Benedum 204 |
| `meeting-note.pdf` | `/sdcard/Download/MobileClawDemo/` | Image-only PDF; Oct 27 project sync 14:00-15:30; return-call window 15:45-16:15; rehearsal 16:30 |

`KNOWN_ANSWERS.json` is the host-side answer key, including suggested search terms
and content facts. Hotel page 2 exercises multiple-page PDF OCR; the meeting note
has no selectable text and exercises a scanned document. All are below the
current size and page limits.

The search tool returns a bounded excerpt, currently 600 characters, rather than a
full PDF transcript. A successful match on page 2 does not guarantee that the
returned excerpt includes that page's details. Check indexed OCR content separately
when validating all pages, and do not promise that every question about a PDF can
be answered from the current excerpt. `file_system cat` is useful for the text notes,
but cannot decode a PDF.

The six notes are installed in `/sdcard/Documents/demo/`. They contain
fictional examples, not operational instructions. In particular,
`hotel-receipt-austin.md` describes **Lakeview Hotel**, a different sample booking.
The PDF describes **Cedar House Austin**: use specific search terms rather than
the ambiguous question "How much was the Austin hotel?". The course note
also has a different assignment deadline. Use CS2610 handout / Nov 2 to select
the PDF fixture. The booth checklist is sample searchable content; use the current
[reset guide](../demo_reset/README.md) for the app's actual reset behavior.

## Proposed real-event contexts

These are fixture contexts, not claims of a completed phone/SMS integration test.
The actual phone calendar and contacts still need to be seeded independently.
Use a real SMS or call from another device to trigger the event.

Calendar setup uses one shared **MobileClaw demo** source (`LOCAL / MobileClawDemo`)
for general, hotel, schedule and callback scenarios. Follow
[Shared demo calendar](../demo_calendar/README.md) to merge old LOCAL demo calendars
once and keep this source selected. The seeders refresh only their own marked fixtures,
preserving other scenarios and manually added events. Use `demo_hotel_calendar.py` for
the fixed October 25 hotel fixture or `demo_schedule_calendar.py` for October 28; the
callback runner refreshes its current-meeting fixture.

Travel documents use fixed fictional dates (October 25-30, 2026), while
`demo_calendar.sh` seeds rehearsal-relative events. Align the calendar explicitly
with the document dates before a travel trace; a "tomorrow" flight entry is not
automatically UA1234 on October 25.

1. **Travel SMS:** "Which hotel did you book, what is its confirmation, and are you
   free after your meeting on October 27?" Require the Cedar House file facts plus
   calendar availability. A calendar-only answer does not demonstrate file search.
2. **Schedule-conflict SMS:** "Can our CS2610 team meet October 29 at 15:30, and when
   is the report due?" Pair the handout's deadline with a seeded conflicting calendar
   event. The file supplies the deadline; the calendar supplies actual availability.
3. **Missed call:** during a seeded meeting, demonstrate a suggestion to text the
   caller and arrange contact after the meeting. The scanned note supplies an
   example return-call window; the live calendar remains the source of meeting
   status. Date-shift both consistently for a rehearsal. There is no dial-out tool;
   finish with a user-confirmed SMS and verify receipt on the other device.

Two SMS events from the same messaging package within five minutes can encounter
the follow-up cooldown. Use separate phones or wait between runs; clearing chat
does not reset that cooldown.

## Verify the installed corpus

`DemoCorpusTest` packages these exact five PDF/PNG files into the test APK and uses
app-owned storage plus a separate index. It needs no broad storage permission.
`DemoPhoneAcceptanceRunner` is opt-in: it rebuilds the real phone index and needs
the preload and explicit all-files permission. Install APKs manually so Gradle's
connected-test cleanup does not remove the app:

```bash
bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e phonePreload true \
  -e class edu.pitt.isl.mobileclaw.notiftest.skills.search.TextReaderTest,edu.pitt.isl.mobileclaw.notiftest.skills.search.DemoCorpusTest,edu.pitt.isl.mobileclaw.notiftest.skills.search.DemoPhoneAcceptanceRunner \
  edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner
```

Two fixture formatting choices help OCR/redaction: the return-call sentence uses
separated wording and monospace digits to distinguish `16:15` from `l6:15`; money uses
normal `1,236.80` formatting rather than a bare digit sequence that matches the PIN
redactor. OCR remains fallible.

## Generate the assets

The generated PDF/PNG files are checked in. Normal preloading requires no PDF or
image libraries. To regenerate them without network access:

```bash
uv venv /tmp/mobileclaw-demo-assets-venv
uv pip install --python /tmp/mobileclaw-demo-assets-venv/bin/python reportlab Pillow
/tmp/mobileclaw-demo-assets-venv/bin/python tools/demo_preload/generate_assets.py
```

The generator uses DejaVu Sans from `/usr/share/fonts/truetype/dejavu`; supply
`--font-dir <directory>` with `DejaVuSans.ttf`, `DejaVuSans-Bold.ttf` and
`DejaVuSansMono.ttf` on another machine. The return-call time uses Mono to
distinguish numeral 1 from lowercase l in real-device OCR. The generator uses
ReportLab's standard PDF fonts and stable PDF metadata.

## Sources and attribution

- The five PDF/PNG assets and their layouts are original project demo fixtures,
  generated locally by `generate_assets.py`. They are dedicated to the public domain
  under **CC0 1.0**: <https://creativecommons.org/publicdomain/zero/1.0/>.
- The six notes are synthetic project fixtures containing sample scenarios,
  not evidence of real bookings or meetings.
- `PHOTOS.tsv` lists the 20 Commons file titles, device filenames,
  license hints and search terms. `PHOTO_SOURCES.tsv` provides canonical source-page
  links. `PHOTOS_ATTRIBUTION.json` is a record of the author,
  credit, license and source fields from the Commons API; all 20 manifest license
  hints were verified against those fields. The downloader also preserves the full
  API metadata in `cache/ATTRIBUTION.json`. Review each source page before public
  reuse in case its attribution terms have changed.
- The 1280-pixel Commons thumbnails are unmodified by this script; they are renamed
  to camera-style filenames on the phone. Keep the attribution cache with any copied
  photos and use the source page's required author credit/license link in shared
  screenshots, videos or distributed handouts. The cache is intentionally gitignored.
