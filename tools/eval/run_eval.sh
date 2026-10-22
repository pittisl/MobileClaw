#!/usr/bin/env bash
# Run the model eval (ModelEvalRunner) on a device and pull the results.
#
#   tools/eval/run_eval.sh -s <adb serial> -t <tag> [-e <endpoint>] [-m <model>] [-k <key>]
#                          [-S triage|chat|both] [-o <task id>] [-r <read timeout s>]
#                          [-R <port>] [-p <prompt suffix>] [--images] [--no-install]
#
# No -e/-m/-k uses the runtime profile saved in Settings > Model service.
# -R <port> runs `adb reverse tcp:<port> tcp:<port>` first, so an endpoint of
# http://127.0.0.1:<port>/v1/chat/completions reaches a llama-server on this machine
# from either an emulator or a phone on USB/wireless adb. Results land in
# tools/eval/results/<tag>-{triage,chat}.jsonl; tools/eval/report.py tabulates them.
set -euo pipefail
cd "$(dirname "$0")/../.."

ADB=${ADB:-adb}
PKG=edu.pitt.isl.mobileclaw.notiftest
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
CLASS=$PKG.eval.ModelEvalRunner

serial=""; tag=""; endpoint=""; model=""; key=""; suite=both; only=""; rtimeout=""; rport=""; images=""; install=1; psuffix=""
while [ $# -gt 0 ]; do
  case "$1" in
    -s) serial=$2; shift 2;;
    -t) tag=$2; shift 2;;
    -e) endpoint=$2; shift 2;;
    -m) model=$2; shift 2;;
    -k) key=$2; shift 2;;
    -S) suite=$2; shift 2;;
    -o) only=$2; shift 2;;
    -r) rtimeout=$2; shift 2;;
    -R) rport=$2; shift 2;;
    -p) psuffix=$2; shift 2;;
    --images) images=1; shift;;
    --no-install) install=0; shift;;
    *) echo "unknown arg $1" >&2; exit 2;;
  esac
done
[ -n "$serial" ] && [ -n "$tag" ] || { echo "need -s <serial> and -t <tag>" >&2; exit 2; }
adb() { "$ADB" -s "$serial" "$@"; }

if [ "$install" = 1 ]; then
  # Not installDebug: Gradle would push to every connected device and fail on the first
  # one that refuses. Build once, install on the one serial asked for.
  bash gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest
  adb install -r -t app/build/outputs/apk/debug/app-debug.apk
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi
[ -n "$rport" ] && adb reverse "tcp:$rport" "tcp:$rport"

args=(-e eval true -e tag "$tag")
[ -n "$endpoint" ] && args+=(-e endpoint "$endpoint")
[ -n "$model" ]    && args+=(-e model "$model")
[ -n "$key" ]      && args+=(-e apiKey "$key")
[ -n "$only" ]     && args+=(-e only "$only")
[ -n "$rtimeout" ] && args+=(-e readTimeout "$rtimeout")
[ -n "$images" ]   && args+=(-e images true)
# The value crosses `adb shell`, where the device's /system/bin/sh re-parses it: wrap it
# in single quotes for that shell and escape any it contains.
[ -n "$psuffix" ]  && args+=(-e promptSuffix "'${psuffix//\'/\'\\\'\'}'")

run() {
  echo "== $tag / $1 on $serial"
  adb shell am instrument -w -r "${args[@]}" -e class "$CLASS#$1" "$RUNNER" \
    | grep -E "INSTRUMENTATION_STATUS: (test|stack)|INSTRUMENTATION_RESULT|Error|OK \(" || true
}
case "$suite" in
  triage) run triage;;
  chat) run chat;;
  both) run triage; run chat;;
esac

mkdir -p tools/eval/results
for s in triage chat; do
  adb pull "/sdcard/Android/data/$PKG/files/eval/$tag-$s.jsonl" tools/eval/results/ 2>/dev/null || true
done
ls -la tools/eval/results/ | grep "$tag" || echo "no results pulled"
