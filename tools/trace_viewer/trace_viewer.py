#!/usr/bin/env python3
"""Show one phone's MobileClaw reasoning trace in a browser on the laptop beside it.

    python3 tools/trace_viewer/trace_viewer.py --serial SERIAL        # live, from that phone
    python3 tools/trace_viewer/trace_viewer.py --file captured.log      # replay a saved logcat

Then open the printed http://127.0.0.1:<port>/ on the same laptop.

The page presents the MobileClaw-TRACE logcat stream as a timeline: each
notification and its triage decision, followed by each agent turn, its tool calls,
their results and the resulting suggestion.

Two rules it keeps:
  * **Pinned to one phone.** Live mode takes `--serial`, or `--auto-device` only when one
    phone is connected. Every phone command carries `-s`; the selected serial stays
    pinned across unplug/replug rather than switching to another station's phone.
  * **Nothing new is revealed.** The app redacts the trace before it reaches logcat;
    this only re-arranges that text. It listens on 127.0.0.1 only.

Pure stdlib (Python 3.9+): no install step on the booth laptops.
"""

from __future__ import annotations

import argparse
import json
import os
import queue
import re
import shutil
import signal
import subprocess
import sys
import threading
import webbrowser
import xml.etree.ElementTree as ET
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from trace_parse import Resume, TraceParser  # noqa: E402

HERE = Path(__file__).resolve().parent
PACKAGE = "edu.pitt.isl.mobileclaw.notiftest"


class Hub:
    """The parser plus every connected page; one lock, because the reader thread and the
    HTTP threads both touch it."""

    def __init__(self, source: str) -> None:
        self.source = source
        self.parser = TraceParser()
        self.clients: list[queue.Queue] = []
        self.status = "starting"
        self.lock = threading.Lock()

    def line(self, line: str) -> None:
        with self.lock:
            for ev in self.parser.feed(line):
                self._send({"kind": "event", "event": ev})

    def set_status(self, status: str) -> None:
        with self.lock:
            self.status = status
            self._send({"kind": "status", "status": status, "source": self.source})

    def subscribe(self) -> tuple[queue.Queue, list[dict]]:
        q: queue.Queue = queue.Queue()
        with self.lock:
            self.clients.append(q)
            snap = [{"kind": "status", "status": self.status, "source": self.source}]
            snap += [{"kind": "event", "event": e} for e in self.parser.snapshot()]
        return q, snap

    def unsubscribe(self, q: queue.Queue) -> None:
        with self.lock:
            if q in self.clients:
                self.clients.remove(q)

    def _send(self, msg: dict) -> None:
        for q in self.clients:
            q.put(msg)


class DeviceReader(threading.Thread):
    """Owns this viewer's adb client; stopping it must also release a blocked logcat read.

    A daemon thread alone leaves adb.exe alive on Windows when Python exits. Track the
    exact child instead of stopping the shared adb server, which scrcpy may still need.
    """

    def __init__(self, hub: Hub, adb: str, serial: str) -> None:
        super().__init__(daemon=True)
        self.hub, self.adb, self.serial = hub, adb, serial
        self.stopped = threading.Event()
        self.process_lock = threading.Lock()
        self.process: subprocess.Popen | None = None

    def _start(self, args: list[str]) -> subprocess.Popen | None:
        with self.process_lock:
            if self.stopped.is_set():
                return None
            self.process = subprocess.Popen(
                [self.adb, "-s", self.serial] + args,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                text=True, encoding="utf-8", errors="replace",
            )
            return self.process

    def _finish(self, proc: subprocess.Popen) -> None:
        if proc.poll() is None:
            proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
        if proc.stdout:
            proc.stdout.close()
        with self.process_lock:
            if self.process is proc:
                self.process = None

    def stop(self) -> None:
        with self.process_lock:
            self.stopped.set()
            if self.process is not None and self.process.poll() is None:
                self.process.terminate()
        self.join(timeout=5)

    def run(self) -> None:
        resume = Resume()
        try:
            while not self.stopped.is_set():
                state = self._start(["get-state"])
                if state is None:
                    return
                try:
                    output, _ = state.communicate(timeout=8)
                except subprocess.TimeoutExpired:
                    output = ""
                finally:
                    self._finish(state)
                if self.stopped.is_set():
                    return
                if state.returncode != 0 or output.strip() != "device":
                    self.hub.set_status(f"waiting for {self.serial}")
                    self.stopped.wait(3)
                    continue
                cmd = ["logcat", "-v", "threadtime"]
                since = resume.reconnect()
                if since:
                    cmd += ["-T", since]
                proc = self._start(cmd + ["-s", "MobileClaw-TRACE:I"])
                if proc is None:
                    return
                self.hub.set_status("live")
                try:
                    assert proc.stdout is not None
                    for line in proc.stdout:
                        if self.stopped.is_set():
                            break
                        if resume.admit(line):
                            self.hub.line(line)
                finally:
                    self._finish(proc)
                if not self.stopped.is_set():
                    self.hub.set_status(f"lost {self.serial}; reconnecting")
                    self.stopped.wait(2)
        except OSError as exc:
            self.hub.set_status(f"error reading {self.serial}: {exc}")


def replay_file(hub: Hub, path: str) -> None:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                hub.line(line.replace("\x00", ""))
        hub.set_status("replayed")
    except OSError as exc:
        hub.set_status(f"error reading capture: {exc}")


def make_handler(hub: Hub):
    page = (HERE / "index.html").read_bytes()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # the terminal is for the operator, not access logs
            pass

        def do_GET(self):
            if self.path in ("/", "/index.html"):
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(page)
                return
            if self.path == "/events":
                self._events()
                return
            self.send_error(HTTPStatus.NOT_FOUND)

        def _events(self):
            q, snapshot = hub.subscribe()
            try:
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                for msg in snapshot:
                    self._write(msg)
                while True:
                    try:
                        self._write(q.get(timeout=15))
                    except queue.Empty:
                        self.wfile.write(b": keep-alive\n\n")
                        self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass
            finally:
                hub.unsubscribe(q)

        def _write(self, msg: dict) -> None:
            self.wfile.write(b"data: " + json.dumps(msg, ensure_ascii=False).encode("utf-8") + b"\n\n")
            self.wfile.flush()

    return Handler


def find_adb(explicit: str | None) -> str:
    if explicit:
        candidate = shutil.which(explicit) or os.path.expanduser(explicit)
        if Path(candidate).is_file():
            return str(Path(candidate).resolve())
        raise ValueError(f"adb not found at {explicit}")
    name = "adb.exe" if os.name == "nt" else "adb"
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    local = os.environ.get("LOCALAPPDATA")
    if local:
        roots.append(str(Path(local) / "Android" / "Sdk"))
    roots += [str(Path.home() / "Android" / "Sdk"), str(Path.home() / "Library" / "Android" / "sdk")]
    candidates = [shutil.which(name), str(Path.home() / "bin" / "platform-tools" / name),
                  str(Path.home() / "platform-tools" / name)]
    candidates += [str(Path(root) / "platform-tools" / name) for root in roots if root]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate).resolve())
    raise ValueError("adb not found; install Android Platform-Tools or pass --adb PATH_TO_ADB")


def adb_command(adb: str, args: list[str]) -> str:
    result = subprocess.run([adb] + args, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=15)
    if result.returncode:
        raise ValueError((result.stderr or result.stdout).strip() or "adb command failed")
    return result.stdout


def select_device(adb: str, serial: str | None) -> str:
    output = adb_command(adb, ["devices", "-l"])
    devices = {}
    for line in output.splitlines():
        if not line.strip() or line.startswith(("List of devices", "*")):
            continue
        parts = line.split()
        if len(parts) >= 2:
            devices[parts[0]] = parts[1]
    if serial is None:
        if len(devices) != 1:
            raise ValueError("Connect exactly one phone, or pass --serial SERIAL.\n" + output.strip())
        serial = next(iter(devices))
    state = devices.get(serial)
    if state != "device":
        hint = ("Unlock the phone and accept its USB debugging prompt." if state == "unauthorized"
                else "Check USB debugging, the data cable and, on Windows, the OEM USB driver.")
        raise ValueError(f"{serial}: {state or 'not connected'}. {hint}\n{output.strip()}")
    return serial


def enable_trace(adb: str, serial: str) -> None:
    output = adb_command(adb, ["-s", serial, "shell", "am", "broadcast", "-n",
                              f"{PACKAGE}/.TraceReceiver", "-a", "edu.pitt.isl.mobileclaw.TRACE_ON"])
    if "Broadcast completed:" not in output or re.search(r"error|exception|permission denial", output, re.I):
        raise ValueError("Could not enable trace: " + output.strip())
    # am broadcast can report result=0 even when the receiver does nothing. Read the
    # persisted switch as well; run-as also confirms this is the required debug APK.
    prefs = adb_command(adb, ["-s", serial, "shell", "run-as", PACKAGE, "cat",
                             f"shared_prefs/{PACKAGE}_preferences.xml"])
    try:
        enabled = ET.fromstring(prefs).find("./boolean[@name='reasoning_trace']")
    except ET.ParseError as exc:
        raise ValueError("Could not verify the trace setting in the debug APK") from exc
    if enabled is None or enabled.get("value") != "true":
        raise ValueError("TRACE_ON did not enable reasoning trace; install the current debug APK")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--serial", help="adb serial of THIS station's phone (from `adb devices -l`)")
    src.add_argument("--auto-device", action="store_true", help="select the phone only if exactly one is connected")
    src.add_argument("--file", help="replay a saved `adb logcat` capture instead of a phone")
    ap.add_argument("--adb", help="path to adb or adb.exe (otherwise search PATH and standard SDK directories)")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--enable-trace", action="store_true", help="enable and verify the debug APK's redacted trace")
    ap.add_argument("--open", action="store_true", help="open the default browser after the server starts")
    args = ap.parse_args()
    if not 1 <= args.port <= 65535:
        ap.error("--port must be between 1 and 65535")
    if args.file and args.enable_trace:
        ap.error("--enable-trace is only available with a phone")
    try:
        if args.file:
            if not Path(args.file).is_file():
                raise ValueError(f"Capture file not found: {args.file}")
        else:
            adb = find_adb(args.adb)
            args.serial = select_device(adb, args.serial)
            if args.enable_trace:
                enable_trace(adb, args.serial)
                print(f"Redacted reasoning trace enabled and verified on {args.serial}.", flush=True)
    except (ValueError, OSError, subprocess.TimeoutExpired) as exc:
        ap.error(str(exc))

    hub = Hub(args.serial or os.path.basename(args.file))
    if args.serial:
        reader = DeviceReader(hub, adb, args.serial)
    else:
        reader = threading.Thread(target=replay_file, args=(hub, args.file), daemon=True)
    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(hub))
    except OSError as exc:
        ap.error(f"Could not listen on 127.0.0.1:{args.port}: {exc}; try --port 8766")
    server.daemon_threads = True
    url = f"http://127.0.0.1:{args.port}/"
    print(f"MobileClaw trace for {hub.source}: {url}   (Ctrl-C to stop)", flush=True)

    def interrupted(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    reader.start()
    try:
        if args.open:
            threading.Thread(target=webbrowser.open, args=(url,), daemon=True).start()
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        if isinstance(reader, DeviceReader):
            reader.stop()
        server.server_close()


if __name__ == "__main__":
    main()
