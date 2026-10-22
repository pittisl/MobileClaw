package edu.pitt.isl.mobileclaw.notiftest.skills.filesystem

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.FsPolicy
import edu.pitt.isl.mobileclaw.notiftest.PermissionSignal
import edu.pitt.isl.mobileclaw.notiftest.Redaction
import edu.pitt.isl.mobileclaw.notiftest.ShellGate
import edu.pitt.isl.mobileclaw.notiftest.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Executes Linux-style file system commands via a privileged shell module running
 * on the device (started via `adb shell`). Read operations return results directly;
 * write and destructive operations are staged through [PendingAction] so the user
 * confirms before anything changes on disk.
 *
 * Read operations also support an app-side route: with all-files access
 * ([Capability.ALL_FILES_ACCESS], the grant the file-search index needs) and no
 * privileged backend, `ls`/`cat`/`stat`/`find` are done by the app itself through
 * [DirectFileReads], on the same [FsPolicy]-vetted path. The bridge is preferred when
 * it is there (it sees more), and the modifying operations never take this route —
 * the grant widens what the app can *see*, not what it may do unattended.
 *
 * Every read result is **redacted before it is returned**, whichever route produced
 * it. File contents are device text bound for the model, the same as a notification
 * body, and both routes use the same sanitizer.
 *
 * The bridge contract is an **argument vector**, never a command string: every path
 * the model supplies crosses it as one `execve` argument, so shell metacharacters in
 * a path are just bytes. Separately, every path is vetted by [FsPolicy] before it
 * gets that far, because a syntactically perfect `rm -rf /sdcard/DCIM` is exactly
 * what a prompt injection would produce. Argument framing and path policy address
 * different problems.
 */
class FileSystemTool internal constructor(
    /** Whether a privileged backend is connected right now — re-checked per call, never cached. */
    private val hasPrivileged: () -> Boolean,
    /** Whether the app may read shared storage for itself (all-files access). */
    private val canReadDirectly: () -> Boolean,
) : Tool {

    constructor(context: Context) : this(
        hasPrivileged = { Capability.PRIVILEGED_SHELL.isGranted(context) },
        canReadDirectly = { Capability.ALL_FILES_ACCESS.isGranted(context) },
    )

    override val name = "file_system"
    override val description =
        "Run file-system commands on the device: list directories, read files, find paths, " +
        "and create/move/copy/delete files. Write and delete operations require user confirmation."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "operation": {
              "type": "string",
              "enum": ["ls", "cat", "stat", "find", "mkdir", "rm", "mv", "cp", "write"],
              "description": "The file operation to perform."
            },
            "path": {
              "type": "string",
              "description": "Absolute target path (source path for mv/cp). Must start with '/'. Reads are broadly allowed; anything that modifies the device is limited to shared storage (/sdcard/...)."
            },
            "destination": {
              "type": "string",
              "description": "Absolute destination path — required for mv and cp."
            },
            "content": {
              "type": "string",
              "description": "File content to write — required for write."
            },
            "pattern": {
              "type": "string",
              "description": "Name pattern for find (e.g. '*.txt'). Defaults to '*'."
            },
            "recursive": {
              "type": "boolean",
              "description": "For ls: use -la flags. For rm: delete directories recursively. For cp: copy directories."
            }
          },
          "required": ["operation", "path"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        val operation = args.optString("operation").lowercase()
        val content   = args.optString("content")
        val pattern   = args.optString("pattern").ifBlank { "*" }
        val recursive = args.optBoolean("recursive", false)

        val access = if (operation in READ_ONLY_OPS) FsPolicy.Access.READ else FsPolicy.Access.WRITE
        val path = when (val v = FsPolicy.vet(args.optString("path"), access)) {
            is FsPolicy.Verdict.Deny  -> return refused(v.reason)
            is FsPolicy.Verdict.Allow -> v.canonical
        }
        // A destination is somewhere the tool *writes*, whatever the operation is called.
        val dest = args.optString("destination").takeIf { it.isNotBlank() }?.let {
            when (val v = FsPolicy.vet(it, FsPolicy.Access.WRITE)) {
                is FsPolicy.Verdict.Deny  -> return refused(v.reason)
                is FsPolicy.Verdict.Allow -> v.canonical
            }
        }

        return when (operation) {
            "ls"   -> exec(operation, if (recursive) listOf("ls", "-la", "--", path) else listOf("ls", "--", path))
            "cat"  -> exec(operation, listOf("cat", "--", path))
            "stat" -> exec(operation, listOf("stat", "--", path))
            // find takes its start path positionally and rejects `--`; the absolute-path
            // rule in FsPolicy is what keeps it from being read as a flag. The pattern is
            // its own argv element, so no shell is around to glob it.
            "find" -> exec(operation, listOf("find", path, "-name", pattern))

            "mkdir" -> stage(
                operation,
                description = "Create directory: $path",
                argv = listOf("mkdir", "-p", "--", path),
                reply = "Ready to create directory \"$path\" — waiting for your confirmation."
            )

            "rm" -> stage(
                operation,
                // Resolved path *and* what's under it: "Delete (recursive): /sdcard" and
                // "Delete (recursive): /sdcard/tmp" are otherwise the same prompt.
                description = if (recursive) {
                    "Delete (recursive): $path\n${FsPolicy.describeTree(path)}"
                } else {
                    "Delete: $path"
                },
                argv = if (recursive) listOf("rm", "-rf", "--", path) else listOf("rm", "--", path),
                reply = "Ready to delete \"$path\" — waiting for your confirmation."
            )

            "mv" -> {
                val d = dest ?: return "A destination path is required for mv."
                stage(
                    operation,
                    description = "Move: $path → $d",
                    argv = listOf("mv", "--", path, d),
                    reply = "Ready to move \"$path\" to \"$d\" — waiting for your confirmation."
                )
            }

            "cp" -> {
                val d = dest ?: return "A destination path is required for cp."
                stage(
                    operation,
                    description = "Copy${if (recursive) " (recursive)" else ""}: $path → $d",
                    argv = if (recursive) listOf("cp", "-r", "--", path, d) else listOf("cp", "--", path, d),
                    reply = "Ready to copy \"$path\" to \"$d\" — waiting for your confirmation."
                )
            }

            "write" -> {
                if (content.isBlank()) return "Content is required for write."
                val preview = if (content.length > 60) content.take(60) + "…" else content
                ShellGate.modifyWrite(
                    SOURCE, path, content,
                    description = "Write ${content.length} chars to: $path\n\"$preview\"",
                    reply = "Ready to write to \"$path\" — waiting for your confirmation."
                )
            }

            else -> "Unknown operation \"$operation\"."
        }
    }

    /**
     * Stages a modifying operation through [ShellGate.modify]. The gate has no direct
     * execution parameter and always creates a proposal with `allowFullAutomation = false`.
     * A modifying command therefore cannot reach the backend without confirmation.
     */
    private fun stage(operation: String, description: String, argv: List<String>, reply: String): String =
        ShellGate.modify(SOURCE, operation, argv, description, reply)

    /**
     * A refusal is prompt surface, so it's structured like [Capability.unavailable]:
     * the model should relay it, not paper over it by trying another spelling of the
     * same path (`..`, a symlink, `/sdcard` vs `/storage/emulated/0` — all resolve to
     * the same verdict).
     */
    private fun refused(reason: String) =
        "[fs_denied: $reason] Tell the user what was refused and why. Re-spelling the " +
        "same path will not change this answer."

    /**
     * Read-only operations run at once, through the bridge when one is connected and
     * otherwise by the app itself when it holds all-files access. Transport, argv
     * framing and the audit record of a *privileged* read belong to [ShellGate]; a
     * direct read is an ordinary in-app read, like the search index's, and is not an
     * audit-trail event. With neither route the easier grant is the one asked for.
     *
     * The result is redacted on the way out, whichever route produced it.
     */
    private suspend fun exec(operation: String, argv: List<String>): String = withContext(Dispatchers.IO) {
        val raw = when {
            hasPrivileged() -> ShellGate.read(SOURCE, operation, argv)
            canReadDirectly() -> direct(operation, argv)
            else -> {
                PermissionSignal.request(Capability.ALL_FILES_ACCESS)
                Capability.unavailable(Capability.ALL_FILES_ACCESS)
            }
        }
        Redaction.redact(raw)
    }

    /** The argv the bridge would have run, interpreted by [DirectFileReads]. Path is always last or before the pattern. */
    private fun direct(operation: String, argv: List<String>): String = when (operation) {
        "ls" -> DirectFileReads.ls(argv.last(), long = argv.contains("-la"))
        "cat" -> DirectFileReads.cat(argv.last())
        "stat" -> DirectFileReads.stat(argv.last())
        "find" -> DirectFileReads.find(argv[1], argv.last())
        else -> "Unknown read operation \"$operation\"."
    }

    private companion object {
        val READ_ONLY_OPS = setOf("ls", "cat", "stat", "find")

        /** Names this tool in the audit trail. */
        const val SOURCE = "file_system"
    }
}
