package edu.pitt.isl.mobileclaw.notiftest

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Path and argument vetting for [FileSystemTool], deliberately kept out of the tool
 * itself so it can be unit-tested on the JVM with no device, no permissions and no
 * shell bridge.
 *
 * It answers two *independent* questions, and neither answer covers the other:
 *
 *  - **Is this command well-formed?** Solved structurally rather than by escaping:
 *    nothing in this file or in [FileSystemTool] builds a command *string*. The
 *    bridge contract is an argument vector the daemon can `execve` directly, so a
 *    path containing `"`, `;`, `$(…)` or a newline is one argument regardless of the
 *    bytes it holds. The quoting problem stops existing instead of being escaped
 *    around. [validateArgv] is the residual check for what argv can't express.
 *  - **Should this command have been issued at all?** argv framing does nothing here.
 *    Notification text is attacker-controlled and reaches the model, so a hostile
 *    notification can talk it into `operation="rm", path="/sdcard/DCIM",
 *    recursive=true` — a valid tool call with no metacharacters anywhere. Only policy
 *    stops that, and [vet] is that policy.
 */
object FsPolicy {

    enum class Access { READ, WRITE }

    sealed interface Verdict {
        /**
         * [canonical] is the path *after* `..` and symlink resolution. Act on this,
         * never on the string the model supplied — the whole point of resolving is
         * that the two can differ.
         */
        data class Allow(val canonical: String) : Verdict

        /** [reason] is handed back to the model, so it's phrased for the model. */
        data class Deny(val reason: String) : Verdict
    }

    /**
     * Off limits for every operation, read included, confirmation or not.
     *
     * `/data/data` and `/data/user` cover this app's own directory, which is the
     * point: the agent must not be able to read or rewrite its own settings, its
     * database, or the audit log that records what it did. `/data/local/tmp` is where
     * a privileged helper and its session token can live, so it is off
     * limits for the same reason — a bridge that can overwrite its own authentication
     * is not a bridge.
     *
     * The shell user can't read most of these anyway; denying them here means the
     * refusal is explicit and logged rather than an opaque errno from the daemon.
     */
    private val DENIED_ROOTS = listOf(
        "/system", "/vendor", "/product", "/apex", "/odm",
        "/proc", "/sys", "/dev",
        "/data/data", "/data/user", "/data/user_de", "/data/system", "/data/misc",
        "/data/local/tmp"
    )

    /**
     * Writes and deletes start narrow: shared storage only, per the privileged-command policy. Widening this
     * should be an explicit user setting, not a code change.
     *
     * All three entries name the same directory — `/sdcard` and `/storage/self/primary`
     * are symlinks to `/storage/emulated/0` on a device — but canonicalisation only
     * collapses them where the symlink actually exists, which is true on a device and
     * false in a JVM unit test. Listing all three keeps policy identical in both.
     */
    private val WRITABLE_ROOTS = listOf("/sdcard", "/storage/emulated/0", "/storage/self/primary")

    /**
     * Vets one path for one kind of access. Canonicalises **first**, then checks:
     * testing the raw string would let `/sdcard/../data/data/…` and a swapped symlink
     * straight through.
     */
    fun vet(rawPath: String, access: Access): Verdict {
        if (rawPath.isBlank()) return Verdict.Deny("the path is empty")
        if (rawPath.contains('\u0000')) return Verdict.Deny("the path contains a NUL byte")
        if (!rawPath.startsWith("/")) {
            // Absolute-only also means a path can never be mistaken for a flag, so no
            // argv element ever needs a leading-dash guard beyond the `--` separators.
            return Verdict.Deny("\"$rawPath\" is relative; give an absolute path starting with /")
        }

        val canonical = try {
            File(rawPath).canonicalPath
        } catch (e: IOException) {
            return Verdict.Deny("\"$rawPath\" could not be resolved (${e.message})")
        }

        DENIED_ROOTS.firstOrNull { canonical.isUnder(it) }?.let { root ->
            return Verdict.Deny(
                "\"$rawPath\" resolves to \"$canonical\", which is under $root — " +
                    "permanently off limits to this tool"
            )
        }

        if (access == Access.WRITE && WRITABLE_ROOTS.none { canonical.isUnder(it) }) {
            return Verdict.Deny(
                "\"$rawPath\" resolves to \"$canonical\"; this tool may only modify " +
                    "shared storage (${WRITABLE_ROOTS.first()}/…)"
            )
        }

        return Verdict.Allow(canonical)
    }

    /**
     * Last check before an argument vector crosses the bridge, for the two things
     * argv framing can't express: a NUL (which truncates the argument in `execve`)
     * and an empty vector or element. Returns null when the vector is fine.
     */
    fun validateArgv(argv: List<String>): String? = when {
        argv.isEmpty() -> "empty command"
        argv.any { it.isEmpty() } -> "empty argument in command"
        argv.any { it.contains('\u0000') } -> "argument contains a NUL byte"
        else -> null
    }

    /**
     * Best-effort description of what actually sits under [canonical], for the
     * recursive-delete confirmation. Without it, "Delete (recursive): /sdcard" and
     * "Delete (recursive): /sdcard/tmp" look identical to the user at the moment they
     * decide — which would make the confirm gate ceremonial for the single most
     * destructive operation the tool offers.
     *
     * Counting stops at [limit]: the number only has to be large enough to alarm, and
     * walking a whole card to produce an exact figure would stall the dialog.
     * Unreadable subtrees are skipped rather than fatal — the app is `untrusted_app`
     * and may legitimately see only part of the tree.
     */
    fun describeTree(canonical: String, limit: Int = 10_000): String {
        val root = File(canonical)
        if (!root.exists()) return "path is not visible to the app — contents unknown"
        if (!root.isDirectory) return "1 file, ${format(root.length())} bytes"

        var files = 0
        var dirs = 0
        var bytes = 0L
        var truncated = false
        for (entry in root.walkTopDown()) {
            if (entry == root) continue
            if (entry.isDirectory) dirs++ else { files++; bytes += entry.length() }
            if (files + dirs >= limit) { truncated = true; break }
        }

        val prefix = if (truncated) "at least " else ""
        return "$prefix${count(files, "file", "files")} in " +
            "${count(dirs, "directory", "directories")}, ${format(bytes)} bytes"
    }

    /** Prefix match on a path boundary, so `/systemx` is not "under" `/system`. */
    private fun String.isUnder(root: String) = this == root || startsWith("$root/")

    private fun count(n: Int, one: String, many: String) =
        "${format(n.toLong())} ${if (n == 1) one else many}"

    private fun format(n: Long) = String.format(Locale.US, "%,d", n)
}
