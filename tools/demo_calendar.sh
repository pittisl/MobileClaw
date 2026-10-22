#!/usr/bin/env bash
# Refresh only general fixtures in the shared demo calendar. Other scenarios and
# hand-added events survive; legacy scenario calendars need a one-time --merge.
set -euo pipefail
SERIAL="${1:?usage: $0 <adb serial> [timezone]}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ARGS=(--serial "$SERIAL" --seed general)
if [ -n "${2:-}" ]; then ARGS+=(--timezone "$2"); fi
exec python3 "$SCRIPT_DIR/demo_calendar.py" "${ARGS[@]}"
