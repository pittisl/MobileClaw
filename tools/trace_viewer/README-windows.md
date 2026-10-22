# Windows demo laptop: native USB and browser trace

Run the viewer with **Windows Python + Windows adb.exe + a Windows browser**. Put this
checkout on NTFS, for example `C:\src\MobileClaw`. The phone already runs the agent;
the laptop only reads the redacted trace and renders the page. It does not need a
Linux workstation, WSL, a GPU, Android Studio, Gradle, an API key or Python packages.
The agent on the phone still needs its configured LLM endpoint and network access.

## Prepare once, before the venue

1. Install [Python for Windows](https://www.python.org/downloads/windows/) (3.9 or newer).
   Open a new PowerShell and check `py -3 --version` or `python --version`.
   The launcher probes the actual interpreter, so a Microsoft Store alias without an
   installed runtime produces a setup error rather than starting an empty page.
2. Download Google's [SDK Platform-Tools for Windows](https://developer.android.com/tools/releases/platform-tools).
   Keep the extracted `platform-tools` folder together, including its DLLs. A convenient
   location is `C:\Users\<you>\platform-tools`; Android Studio's default
   `%LOCALAPPDATA%\Android\Sdk\platform-tools` works too. The launcher also searches
   `PATH`, `ANDROID_HOME` and `ANDROID_SDK_ROOT`. For any other location, pass
   `-Adb C:\tools\platform-tools\adb.exe`.
3. Clone in Windows PowerShell, with your usual Git authentication:

   ```powershell
   git clone https://github.com/pittisl/MobileClaw.git C:\src\MobileClaw
   cd C:\src\MobileClaw
   ```

   Windows files suit Windows tools; this follows
   [Microsoft's filesystem guidance](https://learn.microsoft.com/windows/wsl/filesystems).
   If you later do Linux builds in WSL, use a separate checkout inside WSL for that work.
   For this demo, connect the demo phone directly to Windows; leave its USB device attached to
   Windows rather than attaching it to WSL through usbipd.
4. Connect the Android demo phone with a data-capable USB cable. Enable USB debugging, unlock
   it and approve this Windows computer's RSA prompt. Check, using your actual adb path:

   ```powershell
   & "$env:USERPROFILE\platform-tools\adb.exe" devices -l
   ```

   Expect `SERIAL device ...`. `unauthorized` means accept the phone's prompt.
   If no device appears, check the cable and Device Manager; Android documents
   [Windows OEM USB drivers](https://developer.android.com/studio/run/oem-usb).
   Keep using the debug MobileClaw APK already installed on the phone. A new clone
   does not need to build, install or clear anything on it.

## At the booth

From the repository's Windows PowerShell:

```powershell
.\tools\trace_viewer\start-trace.cmd -Serial SERIAL
```

The command checks the device, enables the app's redacted trace through `TRACE_ON`,
verifies that the debug APK persisted the switch, starts a localhost server and opens
[http://127.0.0.1:8765/](http://127.0.0.1:8765/). Keep its terminal open. Leave the browser
visible while you operate MobileClaw on the phone; new notifications and chat turns
arrive as a timeline. This is the app's decision/tool trace, not hidden model reasoning.

If exactly one device is connected, you can omit `-Serial` or double-click
`tools\trace_viewer\start-trace.cmd`. With several devices connected it refuses to
guess; pass the demo phone's serial explicitly. Double-clicking an unsuccessful launch leaves
the error visible in its terminal.

The `.cmd` entry uses Windows PowerShell with a process-only execution policy, leaving
user and machine settings untouched. If local policy permits `.ps1` scripts, this
equivalent entry works in Windows PowerShell 5.1 or PowerShell 7:

```powershell
.\tools\trace_viewer\start-trace.ps1 -Serial SERIAL
```

Microsoft documents [execution-policy scopes and precedence](https://learn.microsoft.com/powershell/module/microsoft.powershell.core/about/about_execution_policies).
Organization Group Policy can still block a script. In that case use Python directly:

```powershell
py -3 .\tools\trace_viewer\trace_viewer.py --serial SERIAL --enable-trace --open
```

Use **Clear** between visitors. It hides earlier events in that page; refreshing restores
the server's snapshot. Stop with **Ctrl-C** in the terminal. The viewer closes its own
adb logcat client, preserving the adb server used by other tools. Disconnect/reconnect
recovers automatically while the viewer stays running. Trace stays enabled on the phone;
disable it later in MobileClaw Settings if desired.

## Options and offline rehearsal

For a desktop shortcut on a machine where WSL may occupy the default ADB port, use
`start-trace-desktop.ps1 -Serial SERIAL -Python C:\path\to\python.exe`. It uses the repository-root
`adb.exe` by default (override with `-Adb`), tries ports 5037 then 5038, and skips a
server that does not identify itself as native Windows ADB. `-Serial` is required for this desktop entry point. The ADB socket setting is process-local.
Clicking again opens an existing viewer for that phone. Otherwise it starts the viewer
on the first available port from 8765 through 8775 and opens the default browser.
Keep its console open; Ctrl-C stops the viewer. A desktop `.cmd` can invoke this script
through `powershell.exe -NoProfile -ExecutionPolicy Bypass -File` with absolute paths.

```powershell
# Explicit tool paths; paths with spaces are supported.
.\tools\trace_viewer\start-trace.cmd -Serial SERIAL -Adb "C:\Android SDK\platform-tools\adb.exe" -Python "C:\Python313\python.exe"

# Another viewer is already using 8765.
.\tools\trace_viewer\start-trace.cmd -Serial SERIAL -Port 8766

# Rehearse with the committed fixture: no phone, adb or internet needed.
.\tools\trace_viewer\start-trace.cmd -ReplayFile .\tools\trace_viewer\testdata\interleaved-tagged.log

# Run without automatically opening a tab.
.\tools\trace_viewer\start-trace.cmd -Serial SERIAL -NoBrowser
```

`-SkipEnableTrace` skips both the `TRACE_ON` broadcast and its persisted-setting check;
use it only when you already enabled the trace through the phone's Settings. It still
checks device selection/USB authorization. Replay never invokes adb or changes the phone.

For a saved capture, prefer a UTF-8 `adb logcat -v threadtime -s MobileClaw-TRACE:I` file.
To capture from older Windows PowerShell without its default UTF-16 redirection, use
`cmd /c 'adb.exe -s SERIAL logcat -v threadtime -d -s MobileClaw-TRACE:I > saved.log'`
when adb.exe is on PATH. Then pass `-ReplayFile .\saved.log`.

## Windows validation checklist

Run this checklist on the intended laptop. Host-side parser and HTTP/SSE tests use
fake adb to exercise disconnect, reconnect, deduplication and child-process cleanup;
they do not verify the real Windows USB driver or PowerShell lifecycle.

- [ ] Offline fixture opens automatically and shows two chat turns plus one triage.
- [ ] `-Serial SERIAL` reports the trace setting enabled and verified, then the page
      reaches `live`. Trigger a test notification or send a chat turn; its tools and
      answer appear without manually opening phone Settings.
- [ ] Unplug USB: the page changes to `lost` / `waiting`. Replug: it returns to `live`
      without duplicate turns. New turns still arrive.
- [ ] Clear removes old cards; the next turn appears. Reload restores the snapshot.
- [ ] Ctrl-C stops the viewer; the same command starts again on port 8765. Other adb
      clients, including screen mirroring if in use, remain connected.
- [ ] With another phone connected, omitting `-Serial` fails clearly; the explicit-device
      command works. Unapproved USB debugging produces an authorization instruction.
- [ ] Check the offline fixture once with Wi-Fi disabled **on the laptop**. The viewer
      needs no CDN or Linux-hosted resource. The live phone agent still needs its LLM.
