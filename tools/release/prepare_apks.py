#!/usr/bin/env python3
"""Build, audit and sign standard MobileClaw/TaskBoard release APKs locally.

Signing credentials stay in external files; this command never uploads artifacts.
Use a clean source checkout without the optional MobileCLIP weights.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def run(args, *, capture=False):
    return subprocess.run(args, check=True, text=True, stdin=subprocess.DEVNULL,
                          stdout=subprocess.PIPE if capture else None).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--password-file", type=Path, required=True,
                        help="One-line password for both keystore and key")
    parser.add_argument("--alias", default="mobileclaw")
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--build-tools", default="36.0.0")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    os.chdir(root)
    if run(["git", "status", "--porcelain", "--untracked-files=normal"], capture=True).strip():
        parser.error("Build from a clean, committed source checkout.")
    keystore = args.keystore.resolve(strict=True)
    password = args.password_file.resolve(strict=True)
    sdk_tools = args.sdk.resolve() / "build-tools" / args.build_tools
    for tool in ("zipalign", "apksigner", "aapt"):
        if not (sdk_tools / tool).is_file():
            parser.error(f"Missing Android Build Tools executable: {tool}")
    if any((root / "app/src/main/assets/clip").glob("*.onnx")):
        parser.error("Standard APKs omit MobileCLIP weights. Use a clean checkout.")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error("Output directory must be empty; preserve previous signed builds.")

    run(["bash", "gradlew", "-Pmobileclaw.publicBuild=true",
         ":app:assembleRelease", ":demotarget:assembleRelease",
         ":app:testReleaseUnitTest", ":demotarget:testReleaseUnitTest"])
    config = (root / "app/build/generated/source/buildConfig/release/edu/pitt/isl/"
              "mobileclaw/notiftest/BuildConfig.java").read_text()
    for field in ("LLM_API_KEY", "LLM_ENDPOINT", "LLM_MODEL", "NOTION_API_KEY"):
        if not re.search(rf'\b{field}\s*=\s*""\s*;', config):
            raise RuntimeError(f"Release credential guard failed: {field}")
    if not re.search(r"\bDEV_LLM_DEFAULTS\s*=\s*false\s*;", config):
        raise RuntimeError("Release developer defaults must be disabled")

    report = {"source_commit": run(["git", "rev-parse", "HEAD"], capture=True).strip(),
              "source_tree": run(["git", "rev-parse", "HEAD^{tree}"], capture=True).strip(),
              "build_tools": args.build_tools, "developer_defaults": False,
              "bundled_mobileclip": False, "packages": []}
    certificates = set()
    for module, label in (("app", "MobileClaw"), ("demotarget", "TaskBoard")):
        unsigned = root / module / "build/outputs/apk/release" / f"{module}-release-unsigned.apk"
        # xmltree reads the manifest without resolving TaskBoard's framework icon;
        # badging can fail on that valid @android:drawable resource.
        manifest = run([str(sdk_tools / "aapt"), "dump", "xmltree", str(unsigned),
                        "AndroidManifest.xml"], capture=True)
        debug = re.search(r"android:debuggable[^\n]*", manifest)
        if debug and not re.search(r"=\(type 0x12\)0x0$", debug.group()):
            raise RuntimeError(f"{label} is debuggable")
        version = re.search(r'android:versionName[^=]*="([^"]+)"', manifest).group(1)
        package = re.search(r'\bpackage="([^"]+)"', manifest).group(1)
        with zipfile.ZipFile(unsigned) as archive:
            if any(name.startswith("assets/clip/") for name in archive.namelist()):
                raise RuntimeError("Unexpected MobileCLIP assets in standard APK")
        aligned = output / f"{label}-{version}-aligned.apk"
        signed = output / f"{label}-{version}.apk"
        run([str(sdk_tools / "zipalign"), "-P", "16", "-f", "4", str(unsigned), str(aligned)])
        run([str(sdk_tools / "apksigner"), "sign", "--ks", str(keystore),
             "--ks-key-alias", args.alias, "--ks-pass", f"file:{password}",
             # With no --key-pass, apksigner reuses the keystore password. Passing
             # the same file twice would consume a second line instead.
             "--debuggable-apk-permitted", "false", "--v4-signing-enabled", "false",
             "--out", str(signed), str(aligned)])
        verification = run([str(sdk_tools / "apksigner"), "verify", "--verbose",
                            "--print-certs", str(signed)], capture=True)
        run([str(sdk_tools / "zipalign"), "-c", "-P", "16", "4", str(signed)])
        cert = re.search(r"Signer #1 certificate SHA-256 digest: (\w+)", verification).group(1)
        certificates.add(cert)
        (output / f"{label}-signature.txt").write_text(verification)
        aligned.unlink()
        report["packages"].append({"file": signed.name, "package": package, "version": version,
                                   "bytes": signed.stat().st_size, "debuggable": False,
                                   "sha256": hashlib.sha256(signed.read_bytes()).hexdigest(),
                                   "certificate_sha256": cert})
    if len(certificates) != 1:
        raise RuntimeError("The two APKs have different signing certificates")
    (output / "build-manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    checksums = "".join(f'{item["sha256"]}  {item["file"]}\n' for item in report["packages"])
    (output / "SHA256SUMS").write_text(checksums)
    print(f"Verified {len(report['packages'])} signed release APKs in {output}")


if __name__ == "__main__":
    main()
