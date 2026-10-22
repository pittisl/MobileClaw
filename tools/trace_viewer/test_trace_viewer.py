"""HTTP/SSE and process-lifecycle smoke tests; no phone, key, network or extra packages."""

import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from urllib.error import URLError
from urllib.request import urlopen

HERE = Path(__file__).resolve().parent
CAPTURE = HERE / "testdata" / "interleaved-tagged.log"


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class ViewerProcess(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="trace smoke ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.port = free_port()
        self.url = f"http://127.0.0.1:{self.port}"

    def start(self, *args):
        self.output = open(self.root / "viewer.log", "w+")
        self.addCleanup(self.output.close)
        self.viewer = subprocess.Popen(
            [sys.executable, "-u", str(HERE / "trace_viewer.py"), "--port", str(self.port), *args],
            stdout=self.output, stderr=subprocess.STDOUT,
        )
        self.addCleanup(self.stop)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if self.viewer.poll() is not None:
                self.output.seek(0)
                self.fail("Viewer exited: " + self.output.read())
            try:
                return urlopen(self.url, timeout=1)
            except (OSError, URLError):
                time.sleep(0.03)
        self.fail("Viewer did not start")

    def stop(self):
        if getattr(self, "viewer", None) is not None and self.viewer.poll() is None:
            self.viewer.terminate()
            try:
                self.viewer.wait(timeout=7)
            except subprocess.TimeoutExpired:
                self.viewer.kill()
                self.viewer.wait(timeout=2)
                self.fail("Viewer did not shut down")

    def stream(self):
        response = urlopen(self.url + "/events", timeout=8)
        self.addCleanup(response.close)
        self.assertEqual(response.headers.get_content_type(), "text/event-stream")
        return response

    def until(self, stream, predicate):
        messages = []
        for _ in range(300):
            line = stream.readline()
            if line.startswith(b"data: "):
                message = json.loads(line[6:])
                messages.append(message)
                if predicate(message):
                    return messages
        self.fail("Expected SSE message did not arrive")

class OfflineReplay(ViewerProcess):
    def test_replay_serves_page_and_complete_snapshot_to_each_browser(self):
        with self.start("--file", str(CAPTURE)) as page:
            self.assertEqual(page.status, 200)
            self.assertIn(b"EventSource", page.read())
        self.until(self.stream(), lambda msg: msg.get("status") == "replayed")
        for _ in range(2):
            stream = self.stream()
            messages = self.until(stream, lambda msg: msg.get("event", {}).get("type") == "triage")
            self.assertEqual(messages[0]["status"], "replayed")
            events = [msg["event"] for msg in messages if msg["kind"] == "event"]
            self.assertEqual(len(events), 3)
            self.assertTrue(all(event["done"] for event in events if event["type"] == "turn"))
            self.assertIn("Flight to Austin", events[0]["steps"][0]["result"])
            stream.close()

    def test_missing_capture_fails_before_opening_a_server(self):
        result = subprocess.run([sys.executable, str(HERE / "trace_viewer.py"), "--file",
                                 str(self.root / "missing.log")], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Capture file not found", result.stderr)


@unittest.skipIf(os.name == "nt", "The fake adb executable uses a POSIX shebang; real Windows USB is a manual check")
class DeviceLifecycle(ViewerProcess):
    def setUp(self):
        super().setUp()
        self.state = self.root / "state.json"
        self.calls = self.root / "calls.jsonl"
        self.capture = self.root / "capture.log"
        self.capture.write_bytes(CAPTURE.read_bytes())
        self.write_state(connected=True, devices={"demo-device-001": "device"}, enabled=True)
        self.adb = self.root / "fake adb"
        self.adb.write_text("#!" + sys.executable + "\n" + r'''
import json, os, sys, time
from pathlib import Path
root = Path(__file__).resolve().parent
def state():
    return json.loads((root / "state.json").read_text())
args = sys.argv[1:]
with (root / "calls.jsonl").open("a") as f:
    f.write(json.dumps({"pid": os.getpid(), "args": args}) + "\n")
if args == ["devices", "-l"]:
    print("List of devices attached")
    for serial, status in state()["devices"].items():
        print(serial + "\t" + status + " model:OnePlus_12R")
    sys.exit(0)
assert args[:2] == ["-s", "demo-device-001"], args
args = args[2:]
if args == ["get-state"]:
    print("device" if state()["connected"] else "offline")
    sys.exit(0 if state()["connected"] else 1)
if args[:3] == ["shell", "am", "broadcast"]:
    print("Broadcast completed: result=0")
elif args[:2] == ["shell", "run-as"]:
    value = "true" if state()["enabled"] else "false"
    print('<map><boolean name="reasoning_trace" value="' + value + '" /></map>')
elif args[0] == "logcat":
    print((root / "capture.log").read_text(), end="", flush=True)
    while state()["connected"]:
        time.sleep(0.05)
else:
    raise AssertionError(args)
''', encoding="utf-8")
        self.adb.chmod(0o755)

    def write_state(self, **changes):
        current = json.loads(self.state.read_text()) if self.state.exists() else {}
        current.update(changes)
        temporary = self.state.with_suffix(".tmp")
        temporary.write_text(json.dumps(current))
        temporary.replace(self.state)

    def command(self, *args):
        return subprocess.run([sys.executable, str(HERE / "trace_viewer.py"), "--adb", str(self.adb),
                               *args], capture_output=True, text=True)

    def test_selection_refuses_unauthorized_and_multiple_phones(self):
        self.write_state(devices={"demo-device-001": "unauthorized"})
        result = self.command("--serial", "demo-device-001")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("accept its USB debugging prompt", result.stderr)
        self.write_state(devices={"demo-device-001": "device", "another": "device"})
        result = self.command("--auto-device")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exactly one phone", result.stderr)

    def test_trace_enable_checks_persisted_setting_after_broadcast(self):
        self.write_state(enabled=False)
        result = self.command("--serial", "demo-device-001", "--enable-trace")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TRACE_ON did not enable", result.stderr)

    def test_unplug_replug_deduplicates_and_shutdown_reaps_logcat(self):
        with self.start("--auto-device", "--adb", str(self.adb), "--enable-trace"):
            pass
        stream = self.stream()
        self.until(stream, lambda msg: msg.get("event", {}).get("n") == 4 and msg["event"]["done"])
        self.write_state(connected=False)
        self.until(stream, lambda msg: msg.get("status", "").startswith("waiting"))
        with self.capture.open("a") as capture:
            capture.write("10-01 16:00:00.000 13301 13356 I MobileClaw-TRACE: ════ CHAT TURN #9 ─ 16:00:00 ─ Replugged ════════════\n")
        self.write_state(connected=True)
        self.until(stream, lambda msg: msg.get("event", {}).get("n") == 9)
        snapshot = self.until(self.stream(), lambda msg: msg.get("event", {}).get("n") == 9)
        self.assertEqual(len([msg for msg in snapshot if msg["kind"] == "event"]), 4)
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        logcats = [call for call in calls if "logcat" in call["args"]]
        self.assertEqual(len(logcats), 2)
        self.assertIn("-T", logcats[-1]["args"])
        self.stop()
        for child in logcats:
            with self.assertRaises(ProcessLookupError):
                os.kill(child["pid"], 0)
        self.assertFalse(any("kill-server" in call["args"] for call in calls))


if __name__ == "__main__":
    unittest.main()
