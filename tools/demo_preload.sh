#!/usr/bin/env bash
# Install the same fictional corpus on each demo phone. See demo_preload/README.md.
# Explicit file lists keep operational docs and known answers off the phone.
set -euo pipefail

usage() {
  echo "usage: $0 <adb serial> [--photos download|cached|skip] [--cache <directory>]"
  echo "       $0 --list"
}

HERE="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$HERE/demo_preload"
PHOTO_MODE=download
CACHE="$ASSETS/cache"
SERIAL=""
LIST_ONLY=false
while (($#)); do
  case "$1" in
    --photos)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      PHOTO_MODE="$2"; shift 2 ;;
    --cache)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      CACHE="$2"; shift 2 ;;
    --list) LIST_ONLY=true; shift ;;
    --help|-h) usage; exit 0 ;;
    -*) usage >&2; exit 2 ;;
    *) [[ -z "$SERIAL" ]] || { usage >&2; exit 2; }; SERIAL="$1"; shift ;;
  esac
done
case "$PHOTO_MODE" in download|cached|skip) ;; *) usage >&2; exit 2 ;; esac

NOTES=(
  cs2610-project-plan.md dentist-aftercare.txt thesis-committee-prep.md
  hotel-receipt-austin.md grocery-list.txt mobicom-booth-checklist.md
)
PDFS=(hotel-confirmation.pdf course-handout.pdf meeting-note.pdf)
CARDS=(boarding-pass.png restaurant-card.png)
PHOTO_NAMES=()
while IFS=$'\t' read -r name title lic term; do
  [[ -n "$name" && "$name" != \#* ]] || continue
  PHOTO_NAMES+=("$name")
done < "$ASSETS/PHOTOS.tsv"

if "$LIST_ONLY"; then
  printf 'Documents/demo/%s\n' "${NOTES[@]}"
  printf 'Download/MobileClawDemo/%s\n' "${PDFS[@]}"
  printf 'DCIM/Camera/MobileClawDemo/%s\n' "${CARDS[@]}"
  printf 'DCIM/Camera/%s\n' "${PHOTO_NAMES[@]}"
  exit 0
fi
[[ -n "$SERIAL" ]] || { usage >&2; exit 2; }
ADB=(adb -s "$SERIAL")
command -v adb >/dev/null || { echo "adb is required" >&2; exit 1; }
"${ADB[@]}" get-state >/dev/null

for name in "${NOTES[@]}"; do
  [[ -s "$ASSETS/$name" ]] || { echo "Missing note: $name" >&2; exit 1; }
done
for name in "${PDFS[@]}" "${CARDS[@]}"; do
  [[ -s "$ASSETS/generated/$name" ]] || { echo "Missing generated asset: $name" >&2; exit 1; }
done
if [[ "$PHOTO_MODE" == download ]]; then
  "${DEMO_PYTHON:-python3}" "$ASSETS/fetch_photos.py" --manifest "$ASSETS/PHOTOS.tsv" --cache "$CACHE"
fi
if [[ "$PHOTO_MODE" != skip ]]; then
  [[ -s "$CACHE/ATTRIBUTION.json" ]] || {
    echo "Missing photo attribution cache. Run once with --photos download, or use --photos skip." >&2
    exit 1
  }
  for name in "${PHOTO_NAMES[@]}"; do
    [[ -s "$CACHE/$name" ]] || {
      echo "Missing cached photo: $name. Use --photos download once, or --photos skip." >&2
      exit 1
    }
  done
fi

"${ADB[@]}" shell mkdir -p /sdcard/Documents/demo /sdcard/Download/MobileClawDemo /sdcard/DCIM/Camera/MobileClawDemo
push_file() {
  local source="$1" destination="$2"
  "${ADB[@]}" push "$source" "$destination" >/dev/null
  "${ADB[@]}" shell content call --uri content://media/external/file --method scan_file --arg "$destination" >/dev/null
  echo "  $destination"
}
echo "# six notes"
for name in "${NOTES[@]}"; do push_file "$ASSETS/$name" "/sdcard/Documents/demo/$name"; done
echo "# three PDFs"
for name in "${PDFS[@]}"; do push_file "$ASSETS/generated/$name" "/sdcard/Download/MobileClawDemo/$name"; done
echo "# two OCR image cards"
for name in "${CARDS[@]}"; do push_file "$ASSETS/generated/$name" "/sdcard/DCIM/Camera/MobileClawDemo/$name"; done
if [[ "$PHOTO_MODE" != skip ]]; then
  echo "# twenty Commons photos"
  for name in "${PHOTO_NAMES[@]}"; do push_file "$CACHE/$name" "/sdcard/DCIM/Camera/$name"; done
else
  echo "# Commons photos explicitly skipped; existing phone photos remain in place"
fi
echo "# now: MobileClaw > Settings > Rebuild search index now"
echo "# generated media and PDF paths: tools/demo_preload/KNOWN_ANSWERS.json"
