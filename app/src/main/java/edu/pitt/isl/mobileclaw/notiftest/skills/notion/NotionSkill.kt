package edu.pitt.isl.mobileclaw.notiftest.skills.notion

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.BuildConfig
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.Skill
import edu.pitt.isl.mobileclaw.notiftest.Tool

/**
 * Searching, reading and editing pages in the user's Notion workspace.
 *
 * Gated by an API key (from `local.properties`, see README), not a device
 * [Capability] — there is no permission dialog for it, so [capabilities] is empty
 * and the tools themselves report `isConfigured` when the key is missing.
 */
class NotionSkill : Skill {
    override val id = "notion"
    override val displayName = "Notion"
    override val summary =
        "Search your Notion workspace, read a page's content, and rename/append/edit " +
            "it after you confirm. Needs an API key in local.properties; does nothing without it."
    override val capabilities = emptyList<Capability>()

    override fun tools(context: Context): List<Tool> {
        val client = NotionClient(BuildConfig.NOTION_API_KEY)
        return listOf(
            NotionSearchTool(client),
            NotionGetPageTool(client),
            NotionEditPageTool(client),
        )
    }
}
