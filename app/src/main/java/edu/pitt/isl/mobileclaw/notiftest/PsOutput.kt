package edu.pitt.isl.mobileclaw.notiftest

/**
 * One process's memory footprint, from whichever source could supply it.
 *
 * [memoryKb] carries no unit-of-measure of its own on purpose — PSS and RSS are not
 * the same number and the three sources report different ones, so the metric belongs
 * to the [MemorySnapshot] rather than being quietly averaged across sources.
 */
data class ProcessMemory(
    val processName: String,
    val packageName: String,
    val memoryKb: Long
)

/**
 * A memory reading plus where it came from and what it measures.
 *
 * The provenance is not decoration. `ps` gives RSS, which counts every shared page in
 * full — on Android that means the zygote's copy-on-write heap is charged to every app
 * — so the numbers run high in a way the PSS ones do not. Reporting both as "MB" with
 * no further comment would let the model compare readings that aren't comparable, and
 * tell the user an app is using half again what it is.
 */
data class MemorySnapshot(
    val processes: List<ProcessMemory>,
    /** e.g. "PSS" or "RSS" — what [ProcessMemory.memoryKb] actually measures. */
    val metric: String,
    /** Human-readable provenance, for the line the model reads. */
    val source: String
)

/**
 * Parses `ps -A -o RSS,NAME` output.
 *
 * Pure JVM, no Android imports, for the same reason as [FsPolicy] and
 * [ShellCommandPolicy]: this is text arriving from another process, and the awkward
 * cases (kernel threads, names with spaces, a header row, a truncated dump) should be
 * testable without a device or a privileged bridge.
 */
object PsOutput {

    /**
     * Kernel threads have no memory of their own to report and are not something the
     * user can act on, so they are dropped rather than shown with a 0. Matching on
     * "the name has a dot in it" is what the collector script already used: Android
     * process names are package-derived and kernel threads are `[kthreadd]`-shaped.
     */
    private fun isAppProcess(name: String) = name.contains('.') && !name.startsWith("[")

    fun parse(stdout: String): List<ProcessMemory> =
        stdout.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null

            // RSS first, name last, and the name takes the *rest* of the line: a
            // process name may contain a space, and splitting on whitespace into two
            // fields would silently truncate it. This is also why the argv asks for
            // RSS before NAME rather than the ps default order.
            val split = trimmed.indexOf(' ')
            if (split < 0) return@mapNotNull null
            val kb = trimmed.take(split).toLongOrNull() ?: return@mapNotNull null  // drops the header
            val name = trimmed.substring(split).trim()
            if (name.isEmpty() || !isAppProcess(name)) return@mapNotNull null

            ProcessMemory(
                processName = name,
                // Strip a ":service" suffix — those are extra processes of the same
                // app, and the package is what kill_process can act on.
                packageName = name.substringBefore(':'),
                memoryKb = kb
            )
        }.toList()
}
