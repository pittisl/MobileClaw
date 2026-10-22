# Trace viewer — the reasoning trace as a page, for the laptop beside a demo phone

The viewer turns the app's redacted decision/tool log into a timeline: each
notification and its triage decision, followed by each agent turn's tools, returned
results, final suggestion and action chips. It displays the application trace, not
hidden model reasoning.

**Windows demo laptop:** use [README-windows.md](README-windows.md). Clone onto the
Windows filesystem, connect USB to Windows, and run `start-trace.cmd -Serial SERIAL`.
It finds Windows Python and adb.exe, enables the redacted trace and opens the page.
WSL2, USB passthrough, Android Studio and an APK rebuild are unnecessary for this viewer.

```bash
adb devices -l                                                  # once: note this station's serial
python3 tools/trace_viewer/trace_viewer.py --serial SERIAL    # then open http://127.0.0.1:8765/
python3 tools/trace_viewer/trace_viewer.py --file saved.log     # replay a saved capture instead
python3 -m unittest discover -s tools/trace_viewer              # parser + HTTP/SSE smoke tests
```

Python 3.9+, standard library only — nothing to install on a booth laptop. The phone needs
*Settings › Print reasoning to system log* on (or `TraceReceiver`'s `TRACE_ON`).

- **Pinned to one phone.** Use `--serial` (or `--auto-device` with exactly one phone).
  Every phone command carries `-s`, and the selected serial stays pinned across
  unplug/replug. It reconnects without switching phones or repeating events.
- **Nothing new is revealed.** The app redacts the trace before it reaches logcat;
  the viewer only re-arranges that text, and listens on 127.0.0.1.
- **Clear** (top right) hides everything so far — press it between visitors. It is
  per-page; reloading restores the viewer server's retained snapshot.
- Tool calls, results and answers are attached to their turn by the `#n` the app prints
  (`ReasoningTrace`), because triage, follow-ups and typed turns run concurrently
  and their lines interleave. Untagged lines go to the most recent open turn;
  overlapping turns cannot be distinguished without tags.

The Python entry also accepts `--auto-device` (exactly one connected device),
`--enable-trace` (send `TRACE_ON` and verify its persisted setting in a debug APK),
`--open` (open the default browser), `--adb PATH` and `--port 8766`.
Stopping with Ctrl-C closes this viewer's adb logcat client; it leaves the shared adb
server available to tools such as scrcpy. Trace remains enabled on the phone until
Settings or `TraceReceiver`'s `TRACE_OFF` disables it.

Both files in `testdata/` are handwritten, fictional parser fixtures.
`hotel-followup-untagged.log` exercises sequential turns and intervening triage in
untagged logcat format; `interleaved-tagged.log` exercises concurrent tagged turns.
