#!/usr/bin/env python3
"""Open only the two fictional demo SMS conversations so Messaging marks them read.

No message body is supplied, no send button is pressed, no SMS is deleted and no
shell WRITE_SMS override is needed. Run before the model rehearsal, never during it.
"""
import argparse
import json
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    prefix = [args.adb, "-s", args.serial, "shell"]

    def shell(*parts):
        return subprocess.check_output(prefix + list(parts), text=True, timeout=30).strip()

    # Preparing old notifications must not itself start triage calls.
    shell("am", "force-stop", "edu.pitt.isl.mobileclaw.notiftest")
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    shell("wm", "dismiss-keyguard")
    senders = ["+12025550143", "+12025550144"]
    for sender in senders:
        result = shell("am", "start", "-W", "-a", "android.intent.action.SENDTO",
                       "-d", "smsto:" + sender, "-p", "com.android.messaging")
        if "Status: ok" not in result:
            raise RuntimeError("Messaging did not open the fixture conversation")
        # LaunchConversationActivity is a trampoline. Wait for its real conversation
        # to resume; consecutive fire-and-forget starts did not mark either thread read.
        time.sleep(2)
    keys = [line for line in shell("cmd", "notification", "list").splitlines()
            if "|com.android.messaging|" in line]
    report = {"serial": args.serial, "openedFictionalSenders": senders,
              "messageBodySupplied": False, "sendButtonPressed": False,
              "deletedMessages": 0, "remainingMessagingNotificationKeys": keys}
    print(json.dumps(report, indent=2))
    # Do not clear unrelated notifications/messages to make this pass. Use a clean
    # demo phone or manually inspect the remaining native notification first.
    return 1 if keys else 0


if __name__ == "__main__":
    raise SystemExit(main())
