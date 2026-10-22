# Prepare signed APKs

This procedure builds the standard MobileClaw and TaskBoard APKs, runs their
release JVM tests, checks that developer configuration is empty and debugging is
disabled, aligns and signs both APKs, verifies their signatures, and writes
`SHA256SUMS` plus `build-manifest.json`. It does not upload or publish anything.

Use a clean, committed source checkout, JDK 21, Python 3.9+ and
Android SDK Platform 36 / Build Tools 36.0.0. Keep optional MobileCLIP weights out
of this checkout: the standard package deliberately omits them. The build has
network access requirements described in [Building](../../docs/building.md).

Provide the project's existing signing keystore and its one-line password file:

```bash
python3 tools/release/prepare_apks.py \
  --sdk /absolute/path/to/Android/Sdk \
  --keystore /absolute/private/path/mobileclaw-release.p12 \
  --password-file /absolute/private/path/keystore-password.txt \
  --alias mobileclaw \
  --output .artifacts/signed-release
```

The output directory must be empty. Passwords are read from files, never passed as
literal command arguments. Use the same keystore for future updates, and retain
a secure backup of both the keystore and password; losing it prevents signing an
update accepted by existing installations. Signing material belongs outside Git.
The repository ignores `.signing/`, keystore files and APKs.

Before distributing a candidate, install both APKs on an isolated device or
emulator and check startup and the empty model configuration. Inspect the
checksums and certificate against [the installation guide](../../docs/install.md).
Check that the build manifest identifies the intended commit and that the APKs
contain no developer credentials.

Upload the APKs and manifests to a GitHub Release targeting the tested commit.
Keep the Release as a draft until the checks above are complete.
