package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.Skill
import edu.pitt.isl.mobileclaw.notiftest.Tool

/**
 * Finds files by name, folder, text and available image-analysis features through
 * an on-device AppSearch index. Image similarity additionally requires the optional
 * MobileCLIP weights.
 *
 * Declares [Capability.ALL_FILES_ACCESS] because indexing reads shared storage
 * directly. It does not declare PRIVILEGED_SHELL; file reads and modifications remain
 * the separate `file_system` tool's responsibility and follow its gates.
 */
class SearchSkill : Skill {
    override val id = "search"
    override val displayName = "File search"
    override val summary =
        "Find files on the phone by name, folder or the words inside them. Needs all-files " +
            "access to build its index; read-only."
    override val capabilities = listOf(Capability.ALL_FILES_ACCESS)

    override fun tools(context: Context): List<Tool> = listOf(SearchFilesTool(context))
}
