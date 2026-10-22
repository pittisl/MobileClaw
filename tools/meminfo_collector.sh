#!/system/bin/sh
#
# MobileClaw memory info collector
# Runs as shell user (ADB privilege) to read cross-app /proc data,
# which is blocked for normal app processes by SELinux on Android 10+.
#
# Setup (run once from your dev machine):
#   adb -s SERIAL push tools/meminfo_collector.sh /data/local/tmp/
#   adb -s SERIAL shell 'sh /data/local/tmp/meminfo_collector.sh >/data/local/tmp/mobileclaw_meminfo.log 2>&1 & echo $! > /data/local/tmp/mobileclaw_meminfo.pid'
#
# Output: /data/local/tmp/mobileclaw_meminfo.json, refreshed every 5 seconds.
# The MobileClaw app reads this file via PerformanceSkill.topMemoryConsumers().
#
# To stop the collector started above, first inspect its saved PID and process:
#   adb -s SERIAL shell cat /data/local/tmp/mobileclaw_meminfo.pid
#   adb -s SERIAL shell ps -p PID
#   adb -s SERIAL shell kill PID
# Replace PID with the saved number after confirming it is still this collector.

OUTPUT=/data/local/tmp/mobileclaw_meminfo.json

collect() {
    json="["
    first=1

    for pid in $(ls /proc | grep '^[0-9]*$'); do
        # Read process name (first null-delimited token in cmdline)
        cmdline=$(tr '\0' '\n' < /proc/$pid/cmdline 2>/dev/null | head -1)
        [ -z "$cmdline" ] && continue

        # Skip kernel threads — they have no dot in their name
        case "$cmdline" in
            *.*) ;;
            *) continue ;;
        esac

        # PSS from smaps_rollup (Linux 4.14+, Pixel 4 and later)
        pss=$(grep '^Pss:' /proc/$pid/smaps_rollup 2>/dev/null | awk '{print $2}' | head -1)

        # Fall back to VmRSS from status (slightly overestimates shared pages)
        if [ -z "$pss" ] || [ "$pss" = "0" ]; then
            pss=$(grep '^VmRSS:' /proc/$pid/status 2>/dev/null | awk '{print $2}')
        fi

        [ -z "$pss" ] && continue

        # Package name = process name up to the first colon (strips :service suffix)
        pkg=$(echo "$cmdline" | cut -d: -f1)

        # Escape backslash then double-quote so an untrusted process name can't
        # break the JSON — one stray " would make the whole file unparseable and
        # drop every reading. Backslash must be escaped first.
        esc_cmdline=$(printf '%s' "$cmdline" | sed 's/\\/\\\\/g; s/"/\\"/g')
        esc_pkg=$(printf '%s' "$pkg" | sed 's/\\/\\\\/g; s/"/\\"/g')

        if [ $first -eq 1 ]; then
            first=0
        else
            json="${json},"
        fi
        json="${json}{\"processName\":\"${esc_cmdline}\",\"packageName\":\"${esc_pkg}\",\"pssKb\":${pss}}"
    done

    json="${json}]"
    # Write to a temp file then atomically rename, so the app never reads a
    # half-written file mid-refresh.
    echo "$json" > "${OUTPUT}.tmp"
    chmod 644 "${OUTPUT}.tmp"
    mv "${OUTPUT}.tmp" "$OUTPUT"
}

while true; do
    collect
    sleep 5
done
