#!/usr/bin/env python3
"""Simulate a managed incoming call on the selected Android 16 phone, then verify a callback task."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True, help="Explicit adb serial of the rehearsal phone")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--live", action="store_true", help="Use the real model and approve one validated local TaskBoard addTask")
    parser.add_argument("--proposal-only", action="store_true", help="With --live, verify the real model's pending confirmation, then decline; preserve all existing tasks")
    args = parser.parse_args()
    if not re.fullmatch(r"CALLBACK-[A-Z0-9-]{1,40}", args.run_id):
        parser.error("Invalid run ID")
    if args.proposal_only and not args.live:
        parser.error("--proposal-only requires --live")
    args.output.mkdir(parents=True, exist_ok=True)
    base = [args.adb, "-s", args.serial]
    app = "edu.pitt.isl.mobileclaw.notiftest"

    def adb(*parts):
        return subprocess.check_output(base + list(parts), text=True).strip()

    def network():
        return {key: adb("shell", "settings", "get", "global", key) for key in
                ("wifi_on", "mobile_data", "mobile_data2", "private_dns_mode", "private_dns_specifier")}

    original = network()
    assert original["wifi_on"] == "1" and original["mobile_data2"] == "0", original
    # A new process avoids demo-reset baselines suppressing the selected source.
    adb("shell", "am", "force-stop", app)
    command = ["am", "instrument", "-w", "-r", "-e", "class",
               f"{app}.demo.MissedCallTaskBoardRehearsalRunner#capturedMissedCallToConfirmedTask",
               "-e", "callbackRehearsal", "true", "-e", "callbackRunId", args.run_id,
               "-e", "callbackSimulate", "true", "-e", "callbackLive", str(args.live).lower(),
               "-e", "callbackProposalOnly", str(args.proposal_only).lower(),
               f"{app}.test/androidx.test.runner.AndroidJUnitRunner"]
    try:
        result = subprocess.run(base + ["shell", shlex.join(command)], capture_output=True, text=True)
        (args.output / "instrumentation.log").write_text(result.stdout + result.stderr)
    finally:
        after = network()
        (args.output / "network.json").write_text(json.dumps({"before": original, "after": after, "unchanged": original == after}, indent=2) + "\n")
        for folder, names in ((f"demo-callback/{args.run_id}", ("report.json", "trace.log", "capture.png", "confirmation.png", "result.png", "failure.png")),
                              (f"demo-missed-call/MCCALL-{args.run_id.removeprefix('CALLBACK-')}", ("source.json", "after-missed.png"))):
            for name in names:
                output = subprocess.run(base + ["exec-out", "run-as", app, "cat", f"files/{folder}/{name}"], capture_output=True)
                data = output.stdout
                if output.returncode or not data or b"No such file" in data:
                    continue
                if name.endswith(".png") and not data.startswith(b"\x89PNG\r\n\x1a\n"):
                    continue
                (args.output / name).write_bytes(data)
        assert after == original, "Network unexpectedly changed"
    if result.returncode or "OK (1 test)" not in result.stdout:
        raise RuntimeError("Instrumentation failed; inspect saved evidence")
    report = json.loads((args.output / "report.json").read_text())
    assert report["status"] == "passed", report
    print(f"Passed in {report['elapsedMs']} ms; live={report['liveLlm']}, wroteTask={report['wroteTask']}; network unchanged.")


if __name__ == "__main__":
    main()
