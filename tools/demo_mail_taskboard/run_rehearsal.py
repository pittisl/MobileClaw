#!/usr/bin/env python3
"""Run the opt-in Android 16 mail fixture on the selected Android 16 phone; preserve networking by default."""
import argparse
import datetime
import json
from pathlib import Path
import re
import shlex
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True, help="Explicit adb serial of the rehearsal phone")
parser.add_argument("--run-id", required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--live", action="store_true", help="Make live model calls and confirm one validated local demo task")
parser.add_argument("--capture-only", action="store_true", help="Verify a real Gmail production capture and both readers offline; no model calls or task writes")
parser.add_argument("--gmail-marker", help="Use one already-active real Gmail notification with this MCMAIL subject marker")
parser.add_argument("--gmail-expected-body", help="Exact received test body; input-isolation validation only, never part of the user prompt")
parser.add_argument("--cellular", action="store_true", help="Explicitly opt into temporary cellular networking, then restore original state")
args = parser.parse_args()
if not re.fullmatch(r"MAILBOARD-[A-Z0-9-]{1,40}", args.run_id):
    parser.error("Invalid run id")
if args.gmail_marker and not re.fullmatch(r"MCMAIL-[A-Z0-9-]{1,40}", args.gmail_marker):
    parser.error("Invalid Gmail marker")
if bool(args.gmail_marker) != bool(args.gmail_expected_body):
    parser.error("--gmail-marker and --gmail-expected-body must be supplied together")
if args.capture_only and not args.gmail_marker:
    parser.error("--capture-only requires --gmail-marker and --gmail-expected-body")
if args.capture_only and (args.live or args.cellular):
    parser.error("--capture-only cannot be combined with --live or --cellular")
args.output.mkdir(parents=True, exist_ok=True)
base = [args.adb, "-s", args.serial]
app = "edu.pitt.isl.mobileclaw.notiftest"
runner = f"{app}.test/androidx.test.runner.AndroidJUnitRunner"
test_class = f"{app}.demo.MailTaskBoardRehearsalRunner"


def adb(*command, binary=False):
    return subprocess.check_output(base + list(command), text=not binary)


def instrument(method, *extras):
    remote = ["am", "instrument", "-w", "-r", "-e", "class", f"{test_class}#{method}", *extras, runner]
    command = base + ["shell", shlex.join(remote)]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (args.output / f"{method}.log").write_text(result.stdout)
    if result.returncode or "OK (1 test)" not in result.stdout:
        raise RuntimeError(f"Instrumentation failed; inspect {method}.log")


instrument("preflightWithoutModel", "-e", "mailBoardPreflight", "true")
(args.output / "preflight.json").write_text(adb("exec-out", "run-as", app, "cat", "files/demo-mail-board/preflight.json"))
if not args.live and not args.capture_only:
    print("Offline preflight passed; no model calls or task writes.")
    raise SystemExit(0)

data_sub_id = adb("shell", "settings", "get", "global", "multi_sim_data_call").strip()
network_keys = ["wifi_on", "mobile_data"]
if data_sub_id.isdigit():
    network_keys.append(f"mobile_data{data_sub_id}")
original = {key: adb("shell", "settings", "get", "global", key).strip() for key in network_keys}
data_before = original.get(f"mobile_data{data_sub_id}")
if data_before not in ("0", "1"):
    data_before = original["mobile_data"]
state = {"startedAt": datetime.datetime.now().astimezone().isoformat(), "original": original,
         "cellularOptIn": args.cellular, "mode": "real-gmail-reseed" if args.gmail_marker else "synthetic-email",
         "captureOnly": args.capture_only, "defaultDataSubscriptionId": data_sub_id}
(args.output / "network-state.json").write_text(json.dumps(state, indent=2) + "\n")
try:
    if args.cellular:
        adb("shell", "svc", "data", "enable")
        adb("shell", "svc", "wifi", "disable")
    gmail_extras = []
    if args.gmail_marker:
        # The active Gmail notification survives. Fresh MobileClaw process avoids a
        # prior demo-reset baseline suppressing production resume/reseed capture.
        adb("shell", "am", "force-stop", app)
        gmail_extras = ["-e", "realGmailMarker", args.gmail_marker,
                        "-e", "realGmailExpectedBody", args.gmail_expected_body]
        if args.capture_only:
            gmail_extras += ["-e", "realGmailCaptureOnly", "true"]
    instrument("emailNotificationCreatesOneTaskThroughConfirmedAppFunction",
               "-e", "mailBoardRehearsal", "true", "-e", "mailBoardRunId", args.run_id, *gmail_extras)
finally:
    for filename in ("report.json", "trace.log", "confirmation.png", "result.png", "failure.png"):
        source = f"files/demo-mail-board/{args.run_id}/{filename}"
        result = subprocess.run(base + ["exec-out", "run-as", app, "cat", source], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        # Some adb exec-out versions report success for a missing run-as file.
        valid = result.returncode == 0 and bool(result.stdout)
        if filename.endswith(".png"):
            valid = valid and result.stdout.startswith(b"\x89PNG\r\n\x1a\n")
        else:
            valid = valid and b"No such file or directory" not in result.stdout
        if valid:
            (args.output / filename).write_bytes(result.stdout)
    # Try both restores even if one command fails.
    restore_errors = []
    if args.cellular:
        for service, enabled in (("wifi", original["wifi_on"]), ("data", data_before)):
            try:
                adb("shell", "svc", service, "enable" if enabled == "1" else "disable")
            except subprocess.CalledProcessError as error:
                restore_errors.append(str(error))
    state["after"] = {key: adb("shell", "settings", "get", "global", key).strip() for key in original}
    state["restored"] = state["after"] == original
    state["restoreErrors"] = restore_errors
    (args.output / "network-state.json").write_text(json.dumps(state, indent=2) + "\n")
    if restore_errors or not state["restored"]:
        raise RuntimeError("Network restore failed; inspect network-state.json")
report = json.loads((args.output / "report.json").read_text())
assert report["status"] == "passed", report
if args.capture_only:
    assert report["captureOnly"] and not report["liveLlm"] and not report["wroteTask"], report
    print(f"Passed ({report['mode']} capture-only): {report['elapsedMs']} ms; real Gmail capture and production readers; no model calls or task writes; network preserved.")
else:
    print(f"Passed ({report['mode']}): {report['elapsedMs']} ms; one confirmed local TaskBoard write; network preserved/restored.")
