package edu.pitt.isl.mobileclaw.notiftest.skills.notion

import edu.pitt.isl.mobileclaw.notiftest.RetryableHttpException
import edu.pitt.isl.mobileclaw.notiftest.isTransientHttp
import edu.pitt.isl.mobileclaw.notiftest.withRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class NotionPageSummary(val id: String, val title: String, val url: String, val lastEdited: String)

data class NotionPageDetail(
    val id: String,
    val title: String,
    val url: String,
    val titlePropertyKey: String,
    val archived: Boolean
)

/** One block of page content, as rendered text (rich_text joined to plain text). */
data class NotionBlockLine(val id: String, val type: String, val text: String)

/** Thrown for a non-2xx Notion API response; carries the parsed error message when present. */
class NotionApiException(val code: Int, body: String) : Exception(parseMessage(code, body)) {
    companion object {
        private fun parseMessage(code: Int, body: String): String = try {
            "Notion API error $code: ${JSONObject(body).optString("message", body.take(200))}"
        } catch (e: Exception) {
            "Notion API error $code: ${body.take(200)}"
        }
    }
}

/**
 * Thin client for the parts of the Notion API needed to read and edit page
 * content: search, page metadata/title, and block children (the paragraphs,
 * headings, lists, etc. that make up a page's body). Deliberately leaves out
 * the Databases API — nothing here needs it, since a database row is just a
 * page whose properties happen to be schema-constrained, and GET /pages/{id}
 * already returns those properties directly.
 *
 * Auth is a Notion "internal integration" token (from local.properties, see
 * README). The integration must be explicitly shared with each page/workspace
 * section in Notion's UI — the API only sees what's been shared with it.
 */
class NotionClient(private val apiKey: String) {

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Search pages by title across everything shared with the integration. */
    suspend fun search(query: String, pageSize: Int = 10): Result<List<NotionPageSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("query", query)
                .put("filter", JSONObject().put("property", "object").put("value", "page"))
                .put("page_size", pageSize.coerceIn(1, 50))
                .toString()
            val request = authed("$BASE/search").post(body.toRequestBody(jsonMedia)).build()
            val json = call(request)
            val results = json.optJSONArray("results") ?: JSONArray()
            (0 until results.length()).map { i -> toSummary(results.getJSONObject(i)) }
        }
    }

    /** Page metadata: title, URL, archived state, and which property key holds the title. */
    suspend fun getPage(pageId: String): Result<NotionPageDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val id = canonicalizePageId(pageId)
            val json = call(authed("$BASE/pages/$id").build())
            val properties = json.optJSONObject("properties") ?: JSONObject()
            val (key, title) = extractTitle(properties)
            NotionPageDetail(
                id = json.optString("id", id),
                title = title.ifBlank { "(untitled)" },
                url = json.optString("url"),
                titlePropertyKey = key,
                archived = json.optBoolean("archived", false)
            )
        }
    }

    /** The page's body content as an ordered list of blocks (one level deep — no nested children). */
    suspend fun getPageBlocks(pageId: String, limit: Int = 150): Result<List<NotionBlockLine>> = withContext(Dispatchers.IO) {
        runCatching {
            val id = canonicalizePageId(pageId)
            val lines = mutableListOf<NotionBlockLine>()
            var cursor: String? = null
            do {
                val urlBuilder = "$BASE/blocks/$id/children".toHttpUrl().newBuilder()
                    .addQueryParameter("page_size", "100")
                cursor?.let { urlBuilder.addQueryParameter("start_cursor", it) }
                val json = call(authed(urlBuilder.build().toString()).build())
                val results = json.optJSONArray("results") ?: JSONArray()
                for (i in 0 until results.length()) {
                    if (lines.size >= limit) break
                    lines += toBlockLine(results.getJSONObject(i))
                }
                cursor = if (json.optBoolean("has_more", false)) json.optString("next_cursor").takeIf { it.isNotBlank() } else null
            } while (cursor != null && lines.size < limit)
            lines
        }
    }

    /** Renames a page by writing to whichever property is typed "title" (varies for database rows). */
    suspend fun setPageTitle(pageId: String, newTitle: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val id = canonicalizePageId(pageId)
            val pageJson = call(authed("$BASE/pages/$id").build())
            val properties = pageJson.optJSONObject("properties") ?: JSONObject()
            val (key, _) = extractTitle(properties)
            val body = JSONObject().put(
                "properties",
                JSONObject().put(key, JSONObject().put("title", richText(newTitle)))
            ).toString()
            call(authed("$BASE/pages/$id").patch(body.toRequestBody(jsonMedia)).build())
            Unit
        }
    }

    /** Appends a new paragraph block to the end of the page; returns the new block's id. */
    suspend fun appendText(pageId: String, text: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val id = canonicalizePageId(pageId)
            val block = JSONObject()
                .put("object", "block")
                .put("type", "paragraph")
                .put("paragraph", JSONObject().put("rich_text", richText(text)))
            val body = JSONObject().put("children", JSONArray().put(block)).toString()
            val json = call(authed("$BASE/blocks/$id/children").patch(body.toRequestBody(jsonMedia)).build())
            json.optJSONArray("results")?.optJSONObject(0)?.optString("id").orEmpty()
        }
    }

    /** Replaces the text of an existing block in place (its type must carry rich_text). */
    suspend fun editBlockText(blockId: String, newText: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val id = canonicalizePageId(blockId)
            val block = call(authed("$BASE/blocks/$id").build())
            val type = block.optString("type")
            require(type in EDITABLE_TEXT_TYPES) {
                "Block type \"$type\" can't be text-edited here (supported: ${EDITABLE_TEXT_TYPES.joinToString()})."
            }
            val typeBody = JSONObject().put("rich_text", richText(newText))
            if (type == "to_do") {
                typeBody.put("checked", block.optJSONObject("to_do")?.optBoolean("checked", false) ?: false)
            }
            val body = JSONObject().put(type, typeBody).toString()
            call(authed("$BASE/blocks/$id").patch(body.toRequestBody(jsonMedia)).build())
            Unit
        }
    }

    // --- internals ---------------------------------------------------------

    private fun authed(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $apiKey")
        .header("Notion-Version", NOTION_VERSION)

    /** Executes [request], retrying transient failures, and returns the parsed JSON body. */
    private suspend fun call(request: Request): JSONObject = withRetry {
        val response = http.newCall(request).execute()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            if (isTransientHttp(response.code)) throw RetryableHttpException(response.code, "HTTP ${response.code}")
            throw NotionApiException(response.code, text)
        }
        if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun richText(content: String): JSONArray =
        JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", content)))

    private fun plainTextFrom(richTextArray: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until richTextArray.length()) {
            sb.append(richTextArray.getJSONObject(i).optString("plain_text"))
        }
        return sb.toString()
    }

    /** Finds the property whose type is "title" (its key name varies for database rows). */
    private fun extractTitle(properties: JSONObject): Pair<String, String> {
        val keys = properties.keys()
        for (key in keys) {
            val prop = properties.optJSONObject(key) ?: continue
            if (prop.optString("type") == "title") {
                return key to plainTextFrom(prop.optJSONArray("title") ?: JSONArray())
            }
        }
        return "title" to ""
    }

    private fun toSummary(obj: JSONObject): NotionPageSummary {
        val (_, title) = extractTitle(obj.optJSONObject("properties") ?: JSONObject())
        return NotionPageSummary(
            id = obj.optString("id"),
            title = title.ifBlank { "(untitled)" },
            url = obj.optString("url"),
            lastEdited = obj.optString("last_edited_time")
        )
    }

    private fun toBlockLine(block: JSONObject): NotionBlockLine {
        val type = block.optString("type")
        val container = block.optJSONObject(type)
        val text = container?.optJSONArray("rich_text")?.let { plainTextFrom(it) }.orEmpty()
        return NotionBlockLine(id = block.optString("id"), type = type, text = text)
    }

    /**
     * Accepts a bare page ID, a dashed UUID, or a full notion.so URL and returns
     * the canonical dashed UUID the API expects. Notion URLs end in a 32-hex-char
     * ID (undashed), optionally followed by a query string.
     */
    private fun canonicalizePageId(input: String): String {
        val cleaned = input.trim().substringBefore("?")
        val candidate = cleaned.substringAfterLast("-")
        val idPart = if (candidate.length >= 32) candidate else cleaned
        val hex = idPart.filter { it.isLetterOrDigit() }.takeLast(32)
        return if (hex.length == 32) {
            "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        } else cleaned
    }

    companion object {
        private const val BASE = "https://api.notion.com/v1"
        private const val NOTION_VERSION = "2022-06-28"

        /** Block types whose body is a `rich_text` array we can safely overwrite. */
        val EDITABLE_TEXT_TYPES = setOf(
            "paragraph", "heading_1", "heading_2", "heading_3",
            "bulleted_list_item", "numbered_list_item", "quote", "to_do", "toggle"
        )
    }
}
