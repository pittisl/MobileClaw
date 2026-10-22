package edu.pitt.isl.mobileclaw.notiftest.skills.performance

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import edu.pitt.isl.mobileclaw.notiftest.MemorySnapshot
import edu.pitt.isl.mobileclaw.notiftest.ProcessMemory
import edu.pitt.isl.mobileclaw.notiftest.PsOutput
import edu.pitt.isl.mobileclaw.notiftest.ShellGate
import edu.pitt.isl.mobileclaw.notiftest.ShellRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Reports top memory consumers and can force-stop packages.
 *
 * The app itself can see none of this: SELinux blocks `untrusted_app` from reading
 * other processes' `/proc` (Android 10+), and no unprivileged API enumerates other
 * apps' PIDs — `getRunningAppProcesses()` has returned only our own process since
 * API 22. So every path here is about borrowing someone else's privilege, and they
 * are tried strongest first:
 *
 *  1. **The privileged bridge** — `ps -A -o RSS,NAME` as uid 2000 through
 *     [ShellGate]. Nothing to install by hand, nothing to re-arm, current by
 *     construction. Reports RSS, since toybox `ps` has no PSS field.
 *  2. **`tools/meminfo_collector.sh`** — a fallback path, which is the
 *     one that works with no Shizuku: pushed to `/data/local/tmp` and started via
 *     `adb shell`, it walks `/proc` as the shell user and drops PSS as JSON. Dies on
 *     reboot, so data older than [STALE_THRESHOLD_MS] is treated as absent.
 *  3. **Direct `/proc`** — only produces anything on permissive builds, and mostly
 *     just our own process elsewhere. Kept because it costs nothing when it fails.
 */
class ProcessProbe(private val context: Context) {

    companion object {
        private const val TAG = "MobileClaw-PS"
        private const val TOP_N = 3
        private const val HELPER_OUTPUT = "/data/local/tmp/mobileclaw_meminfo.json"
        private const val STALE_THRESHOLD_MS = 30_000L

        /** For [ActionLogStore], so a privileged `ps` is attributable in the export. */
        private const val SOURCE = "memory_monitor"
    }

    /** Null when no source could produce anything — a distinct state from "nothing is running". */
    suspend fun topMemoryConsumers(): MemorySnapshot? = withContext(Dispatchers.IO) {
        readPrivileged()?.let { return@withContext it }

        val helper = readHelperOutput()
        if (helper.isNotEmpty()) {
            return@withContext snapshot(helper, metric = "PSS", source = "meminfo collector")
        }

        Log.w(TAG, "Helper output unavailable — falling back to direct /proc reads")
        val direct = readProcDirect()
        if (direct.isEmpty()) null else snapshot(direct, metric = "PSS", source = "direct /proc")
    }

    /**
     * `ps` through the gate. Returns null rather than an error when there is no backend,
     * because the fallbacks below still work and asking the user to install Shizuku for
     * something we can partly do anyway would be the wrong prompt at the wrong moment.
     */
    private suspend fun readPrivileged(): MemorySnapshot? {
        if (!ShellRegistry.isConnected) return null
        // RSS before NAME so the name can hold the rest of the line — see PsOutput.parse.
        val result = ShellGate.readResult(SOURCE, "ps", listOf("ps", "-A", "-o", "RSS,NAME"))
            ?: return null
        if (!result.ok) {
            Log.w(TAG, "privileged ps failed (exit ${result.exitCode}): ${result.stderr.trim()}")
            return null
        }
        val processes = PsOutput.parse(result.stdout)
        if (processes.isEmpty()) return null
        return snapshot(processes, metric = "RSS", source = "privileged shell")
    }

    private fun snapshot(processes: List<ProcessMemory>, metric: String, source: String) =
        MemorySnapshot(
            processes = processes.sortedByDescending { it.memoryKb }.take(TOP_N),
            metric = metric,
            source = source
        )

    private fun readHelperOutput(): List<ProcessMemory> {
        val file = File(HELPER_OUTPUT)
        if (!file.exists()) {
            Log.w(TAG, "No helper output at $HELPER_OUTPUT — push and start tools/meminfo_collector.sh via ADB")
            return emptyList()
        }

        val ageMs = System.currentTimeMillis() - file.lastModified()
        if (ageMs > STALE_THRESHOLD_MS) {
            // Collector refreshes every 5s; >30s stale means it died. Report unavailable
            // rather than passing off old numbers as the current state.
            Log.w(TAG, "Helper output is ${ageMs / 1000}s old — is meminfo_collector.sh still running? Treating as unavailable.")
            return emptyList()
        }

        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ProcessMemory(
                    processName = obj.getString("processName"),
                    packageName = obj.getString("packageName"),
                    memoryKb = obj.getLong("pssKb")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse helper output", e)
            emptyList()
        }
    }

    private fun readProcDirect(): List<ProcessMemory> {
        val pidDirs = File("/proc").listFiles { f ->
            f.isDirectory && f.name.all { it.isDigit() }
        } ?: return emptyList()

        return pidDirs.mapNotNull { dir ->
            try {
                val cmdline = File(dir, "cmdline").readText().replace('\u0000', ' ').trim()
                if (cmdline.isEmpty()) return@mapNotNull null
                val processName = cmdline.split(" ").first()
                if (!processName.contains(".")) return@mapNotNull null
                val pssKb = readPssKb(dir.name)
                if (pssKb <= 0) return@mapNotNull null
                ProcessMemory(
                    processName = processName,
                    packageName = processName.substringBefore(":"),
                    memoryKb = pssKb
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun readPssKb(pid: String): Long {
        try {
            File("/proc/$pid/smaps_rollup").useLines { lines ->
                val line = lines.firstOrNull { it.startsWith("Pss:") } ?: return@useLines
                return line.trim().split(Regex("\\s+"))[1].toLong()
            }
        } catch (e: Exception) { }

        try {
            File("/proc/$pid/status").useLines { lines ->
                val line = lines.firstOrNull { it.startsWith("VmRSS:") } ?: return@useLines
                return line.trim().split(Regex("\\s+"))[1].toLong()
            }
        } catch (e: Exception) { }

        return 0L
    }

    /**
     * The unprivileged stop, used only when there is no bridge.
     *
     * `killBackgroundProcesses` cannot touch the foreground app, persistent processes,
     * or system processes, and Android restarts most of what it does kill — so the
     * caller reports a *request*, not a kill. `am force-stop` is the real thing and is
     * what [KillProcessTool] uses when a backend is connected.
     */
    fun killProcess(packageName: String) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(packageName)
        Log.i(TAG, "Requested background-process kill for $packageName")
    }

    fun formatForAgent(snapshot: MemorySnapshot): String = buildString {
        appendLine("=== TOP MEMORY CONSUMERS ===")
        snapshot.processes.forEachIndexed { i, p ->
            val mb = "%.1f".format(p.memoryKb / 1024f)
            appendLine("${i + 1}. ${p.processName} (${p.packageName}) — $mb MB ${snapshot.metric}")
        }
        // The metric is not interchangeable between sources (see MemorySnapshot), and
        // the model is the one that has to avoid comparing this reading against a
        // differently-measured one from earlier in the conversation.
        appendLine("(measured as ${snapshot.metric} via ${snapshot.source}" +
            if (snapshot.metric == "RSS") "; RSS charges shared pages in full, so these run high)" else ")")
    }
}
