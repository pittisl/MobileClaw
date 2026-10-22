# Install MobileClaw

We recommend running MobileClaw on **Android 16+**. The minimum supported version
is **Android 8.0+**. The TaskBoard AppFunctions demo requires Android 16+.

## APK downloads

Find signed APKs on the
[Releases page](https://github.com/pittisl/MobileClaw/releases). If no downloadable
release is listed, [build from source](building.md).

Release files:

| File | Purpose |
|---|---|
| `MobileClaw-0.1.apk` | Main app; Android 16+ recommended, Android 8.0+ minimum; arm64 phones and x86_64 emulators |
| `TaskBoard-0.1.apk` | Optional companion app for the Android 16+ AppFunctions demo |
| `SHA256SUMS` | SHA-256 checksums of the two APKs |
| `build-manifest.json` | Source revision, package IDs, sizes and signing certificate fingerprints |

Both APKs are signed release builds with debugging disabled. They contain no
team API keys or private model endpoints. Configure your own model service after
installation. The standard MobileClaw APK includes text search, bundled OCR and
image labels; MobileCLIP similarity weights are optional and are omitted from this
download. To build with those weights, see [Building](building.md).
Photo-request follow-ups can show actual local search results as candidate cards
in the originating conversation. Preview a candidate, tap **Use this photo**, then
explicitly request sharing and confirm the image and recipient. See the
[photo-request walkthrough](demo.md#4-photo-request-to-candidate-cards) for setup,
admission/cooldown limits and a fixture for filename search without
MobileCLIP weights.

## Install on a phone

1. Download `MobileClaw-0.1.apk` to the phone.
2. Open it with the Android package installer. If prompted, allow installation
   from the browser or file manager used for this download.
3. Open MobileClaw and follow the [quick start](quickstart.md) to configure a
   model service and grant the capabilities needed for your task.
4. Install TaskBoard only if you want to try the [AppFunctions demo](demo.md).

An existing developer/debug installation uses a different signing key. Android
will refuse to replace it with this APK. Keep your development installation on a
different device, or export any data you want to retain and then uninstall it
before installing the release. Uninstalling erases local app data and saved model
credentials. A database export is an unencrypted inspection copy, not an automatic
restore facility. Later release APKs signed with the same project key can update
this installation while retaining its data.

With Android Platform Tools on a computer, select a device explicitly:

```bash
adb devices
adb -s SERIAL install -r MobileClaw-0.1.apk
# Optional; its AppFunctions demo needs Android 16+.
adb -s SERIAL install -r TaskBoard-0.1.apk
```

## Verify the download

On Linux, run `sha256sum -c SHA256SUMS` in the download directory. On macOS use
`shasum -a 256 -c SHA256SUMS`. Windows PowerShell can calculate a file's checksum
with `Get-FileHash .\MobileClaw-0.1.apk -Algorithm SHA256` for comparison.

The signing certificate SHA-256 fingerprint for both apps is:

```text
06:BA:B5:21:97:96:0C:A6:57:8D:1E:04:C4:E1:35:0C:1F:A3:89:D1:9D:89:87:AB:C4:9D:D1:B3:20:B9:CB:0E
```

Android SDK Build Tools can independently inspect it:

```bash
apksigner verify --verbose --print-certs MobileClaw-0.1.apk
apksigner verify --verbose --print-certs TaskBoard-0.1.apk
```

The package IDs are `edu.pitt.isl.mobileclaw.notiftest` and
`edu.pitt.isl.mobileclaw.taskboard`. The `notiftest` suffix is part of
the main app's installation identity.
