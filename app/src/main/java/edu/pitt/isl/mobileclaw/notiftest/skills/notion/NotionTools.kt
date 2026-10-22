package edu.pitt.isl.mobileclaw.notiftest.skills.notion

import edu.pitt.isl.mobileclaw.notiftest.ActionLogStore
import edu.pitt.isl.mobileclaw.notiftest.PendingAction
import edu.pitt.isl.mobileclaw.notiftest.ReasoningModule
import edu.pitt.isl.mobileclaw.notiftest.Tool
import org.json.JSONObject

/**
 * Agent tools exposing [NotionClient]: search, read, and edit Notion pages.
 * Databases are intentionally out of scope — GET /pages/{id} already returns
 * a database row's properties, so nothing here needs the Databases API.
 *
 * Reads (search, get_page) run directly. Writes (rename / append / edit
 * content) are staged through [PendingAction] so the user confirms before
 * anything changes in the user's Notion workspace, matching every other
 * outward-acting tool in this codebase.
 */

private const val NOTION_UNCONFIGURED =
    "Notion isn't configured — use an internal debug build with LLM_DEV_DEFAULTS=true and NOTION_API_KEY in local.properties (an internal integration " +
        "token from notion.so/my-integrations), then share the target pages with that integration " +
        "from inside Notion (••• menu → Connections)."

private fun preview(text: String, max: Int = 80) = if (text.length > max) text.take(max) + "…" else text

private fun renderBlock(b: NotionBlockLine): String {
    val text = b.text.ifBlank { "(empty)" }
    return "[${b.id}] ${b.type}: $text"
}

class NotionSearchTool(private val client: NotionClient) : Tool {

    override val name = "notion_search"
    override val description =
        "Search the user's Notion workspace for pages by title. Use this to find a page's ID " +
        "before calling notion_get_page or notion_edit_page — only pages shared with the integration are visible."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "Text to search for in page titles." }
          },
          "required": ["query"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        if (!client.isConfigured) return NOTION_UNCONFIGURED
        val query = args.optString("query").trim()
        if (query.isBlank()) return "I need something to search for."

        return client.search(query).fold(
            onSuccess = { pages ->
                if (pages.isEmpty()) "No Notion pages found matching \"$query\"."
                else "Found ${pages.size} page(s):\n" + pages.joinToString("\n") { p ->
                    "[${p.id}] \"${p.title}\" — ${p.url}"
                }
            },
            onFailure = { e -> "Notion search failed: ${e.message}" }
        )
    }
}

class NotionGetPageTool(private val client: NotionClient) : Tool {

    override val name = "notion_get_page"
    override val description =
        "Retrieve a Notion page's title and full content (paragraphs, headings, lists, etc., each " +
        "with a block ID). Accepts a page ID or a notion.so URL. Use notion_search first if you only know the title."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "page_id": { "type": "string", "description": "Notion page ID or full notion.so URL." }
          },
          "required": ["page_id"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        if (!client.isConfigured) return NOTION_UNCONFIGURED
        val pageId = args.optString("page_id").trim()
        if (pageId.isBlank()) return "I need a page ID or URL — use notion_search to find one."

        val page = client.getPage(pageId).fold(
            onSuccess = { it },
            onFailure = { e -> return "Couldn't load the page: ${e.message}" }
        )
        val blocks = client.getPageBlocks(pageId).fold(
            onSuccess = { it },
            onFailure = { e -> return "Loaded the page but couldn't load its content: ${e.message}" }
        )

        return buildString {
            appendLine("Notion page: \"${page.title}\"${if (page.archived) " (archived)" else ""}")
            appendLine("ID: ${page.id}")
            appendLine("URL: ${page.url}")
            appendLine()
            if (blocks.isEmpty()) {
                appendLine("(no content)")
            } else {
                appendLine("Content:")
                blocks.forEach { appendLine(renderBlock(it)) }
            }
            appendLine()
            append(
                "Use notion_edit_page with operation=\"edit_block_text\" and one of the block IDs above to " +
                "change existing text, operation=\"append_text\" with this page's ID to add a new paragraph, " +
                "or operation=\"set_title\" to rename the page."
            )
        }
    }
}

class NotionEditPageTool(private val client: NotionClient) : Tool {

    override val name = "notion_edit_page"
    override val description =
        "Edit a Notion page: rename it, append a new paragraph, or replace the text of an existing content " +
        "block (get block IDs from notion_get_page). The user is asked to confirm before anything changes in Notion."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "operation": {
              "type": "string",
              "enum": ["set_title", "append_text", "edit_block_text"],
              "description": "Which edit to perform."
            },
            "page_id":  { "type": "string", "description": "Page ID or notion.so URL — required for set_title and append_text." },
            "block_id": { "type": "string", "description": "ID of an existing block to overwrite — required for edit_block_text (from notion_get_page)." },
            "title":    { "type": "string", "description": "New page title — required for set_title." },
            "text":     { "type": "string", "description": "New text content — required for append_text and edit_block_text." }
          },
          "required": ["operation"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        if (!client.isConfigured) return NOTION_UNCONFIGURED
        return when (args.optString("operation").lowercase()) {
            "set_title"       -> setTitle(args)
            "append_text"     -> appendText(args)
            "edit_block_text" -> editBlock(args)
            else -> "Unknown operation \"${args.optString("operation")}\". Use set_title, append_text, or edit_block_text."
        }
    }

    private fun setTitle(args: JSONObject): String {
        val pageId = args.optString("page_id").trim().ifBlank { return "I need a page_id for set_title." }
        val title = args.optString("title").trim().ifBlank { return "I need a new title for set_title." }

        PendingAction.propose(
            PendingAction.Proposal(
                description = "Rename Notion page to: \"${ReasoningModule.redact(title)}\"",
                execute = {
                    client.setPageTitle(pageId, title).fold(
                        onSuccess = {
                            val result = "Renamed the page to \"$title\"."
                            ActionLogStore.record("notion", pageId, "set_title", title, result, success = true)
                            result
                        },
                        onFailure = { e ->
                            val result = "Failed to rename page: ${e.message}"
                            ActionLogStore.record("notion", pageId, "set_title", title, result, success = false)
                            result
                        }
                    )
                }
            )
        )
        return "Ready to rename the Notion page — waiting for your confirmation."
    }

    private fun appendText(args: JSONObject): String {
        val pageId = args.optString("page_id").trim().ifBlank { return "I need a page_id for append_text." }
        val text = args.optString("text").ifBlank { return "I need text to append." }

        PendingAction.propose(
            PendingAction.Proposal(
                description = "Add to Notion page:\n\"${ReasoningModule.redact(preview(text))}\"",
                execute = {
                    client.appendText(pageId, text).fold(
                        onSuccess = {
                            val result = "Appended the text to the page."
                            ActionLogStore.record("notion", pageId, "append_text", text, result, success = true)
                            result
                        },
                        onFailure = { e ->
                            val result = "Failed to append text: ${e.message}"
                            ActionLogStore.record("notion", pageId, "append_text", text, result, success = false)
                            result
                        }
                    )
                }
            )
        )
        return "Ready to append to the Notion page — waiting for your confirmation."
    }

    private fun editBlock(args: JSONObject): String {
        val blockId = args.optString("block_id").trim()
            .ifBlank { return "I need a block_id (from notion_get_page) for edit_block_text." }
        val text = args.optString("text").ifBlank { return "I need the replacement text." }

        PendingAction.propose(
            PendingAction.Proposal(
                description = "Edit Notion block to:\n\"${ReasoningModule.redact(preview(text))}\"",
                execute = {
                    client.editBlockText(blockId, text).fold(
                        onSuccess = {
                            val result = "Updated the block's text."
                            ActionLogStore.record("notion", blockId, "edit_block_text", text, result, success = true)
                            result
                        },
                        onFailure = { e ->
                            val result = "Failed to edit block: ${e.message}"
                            ActionLogStore.record("notion", blockId, "edit_block_text", text, result, success = false)
                            result
                        }
                    )
                }
            )
        )
        return "Ready to edit the Notion block — waiting for your confirmation."
    }
}
