package edu.pitt.isl.mobileclaw.notiftest.skills.filesystem

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.Skill
import edu.pitt.isl.mobileclaw.notiftest.Tool

/**
 * Listing, reading and modifying files outside the app sandbox.
 *
 * Reads work with either all-files access (the app reads for itself) or the privileged
 * backend; modifying operations need the backend and stay behind the confirm gate.
 *  Path vetting stays in `FsPolicy` and the confirm
 * requirement in `ShellGate`, both outside this package — a skill declares tools, it
 * does not carry policy.
 */
class FileSystemSkill : Skill {
    override val id = "filesystem"
    override val displayName = "File system"
    override val summary =
        "List, read and search files on the device with all-files access, and create, " +
            "move or delete them after you confirm — changes need privileged shell access."
    override val capabilities = listOf(Capability.ALL_FILES_ACCESS, Capability.PRIVILEGED_SHELL)

    override fun tools(context: Context): List<Tool> = listOf(FileSystemTool(context))
}
