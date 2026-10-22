package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import edu.pitt.isl.mobileclaw.notiftest.AppSettings
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.PermissionSignal
import edu.pitt.isl.mobileclaw.notiftest.PhotoTurn
import edu.pitt.isl.mobileclaw.notiftest.Tool
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * `search_files` — find files on shared storage by words in their name, folder or text
 * content. Read-only, so no confirmation; the result is redacted like every
 * other string bound for the model.
 *
 * It returns paths and matching passages, **not** file contents. Reading a file stays
 * with `file_system`, which runs the path through `FsPolicy` and the privileged bridge —
 * the index is not a second door to the disk. The walk that built the index applied the
 * same policy, so nothing `file_system` would refuse is in here to begin with.
 */
class SearchFilesTool(private val context: Context, private val index: FileIndex = FileIndex(context)) : Tool {

    override val name = "search_files"
    override val description =
        "Search the phone's shared storage for files by words in the file name, folder name, " +
            "(for text files) content, (for photos, screenshots and PDFs) text printed on them — a boarding " +
            "pass by its flight number or airport, a receipt by its shop — or (for photos) what the picture " +
            "shows: labels such as cat, dog, beach, food, and visual similarity to a short description such " +
            "as 'orange cat on a sofa'. Returns matching paths and passages, best match first, with the text " +
            "read off photos and PDFs quoted; read a text-file result with file_system. For a photo taken " +
            "yesterday, use query 'dog' and captured_on 'yesterday', keeping dates out of the visual query. " +
            "Capture-date filters use EXIF/MediaStore, never file modification time; unknown dates are " +
            "excluded. Image candidates appear in chat for the user to open and select. Not a web search."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "Words to look for. Prefixes match (\"receip\" finds \"receipt\")." },
            "kind": { "type": "string", "enum": ["any", "text", "image", "document", "audio", "video", "other"], "description": "Restrict to one kind of file. Leave unset unless the user names a file type: a 'receipt' may be a photo, a PDF or a text note." },
            "limit": { "type": "integer", "description": "Maximum results (default 8, max 20)." },
            "captured_on": { "type": "string", "description": "Photo capture date: yyyy-MM-dd, today, or yesterday, resolved in the phone's current timezone. Do not combine with captured_after/captured_before." },
            "captured_after": { "type": "string", "description": "Inclusive capture-date lower bound, yyyy-MM-dd, in the phone's current timezone." },
            "captured_before": { "type": "string", "description": "Exclusive capture-date upper bound, yyyy-MM-dd, in the phone's current timezone." }
          },
          "required": ["query"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return "search_files needs a non-empty query."
        val captured = try {
            for (key in listOf("captured_on", "captured_after", "captured_before")) {
                require(!args.has(key) || args.isNull(key) || args.opt(key) is String) { "$key must be a string." }
            }
            CaptureDate.filter(
                args.optString("captured_on", null), args.optString("captured_after", null),
                args.optString("captured_before", null),
            )
        } catch (e: IllegalArgumentException) {
            return "search_files: ${e.message} No search was performed; keep the user's date constraint."
        }

        if (!Capability.ALL_FILES_ACCESS.isGranted(context)) {
            PermissionSignal.request(Capability.ALL_FILES_ACCESS)
            return Capability.unavailable(Capability.ALL_FILES_ACCESS)
        }

        val kind = IndexPolicy.Kind.parse(args.optString("kind", null))
        val limit = args.optInt("limit", 8).coerceIn(1, 20)
        val state = IndexState.read(context)
        if (index.isDefault && state.needsBuild) {
            // Access is held (checked above) but nothing has been indexed under it — the
            // periodic job ran before the grant and will not look again for hours. The
            // first build is done here, inline and bounded, because a two-step answer
            // ("started, ask again") is one the model reliably fails to follow up on; every
            // later build stays with the worker. Past the bound, hand over to the worker.
            // No classifier on this path: one photo folder would exhaust the bound before
            // a single name was indexed. Images go in by name now and the worker labels
            // them, so the first answer is fast and the second is complete.
            AppSettings.init(context)
            val built = withTimeoutOrNull(FIRST_BUILD_TIMEOUT_MS) {
                FileIndexer(context, photoContent = PhotoContent.forFirstBuild(AppSettings.labelPhotos)).rebuild()
            }
            if (built == null) {
                SearchIndexWorker.runNow(context)
                return "The file index is being built for the first time and is taking longer than " +
                    "${FIRST_BUILD_TIMEOUT_MS / 1000} s; it will finish in the background. Tell the user, then try again."
            }
            if (built.imagesPending > 0) SearchIndexWorker.runNow(context)
        }
        var note: String? = null
        val hits = try {
            var found = index.search(query, kind, limit, captured)
            // The model guesses `kind` from the noun ("receipt" → document) far more often
            // than the user asked for a type, and a wrong guess turns a hit into "no files
            // match". Widen once and say so, rather than make the model guess again.
            if (found.isEmpty() && kind != null) {
                found = index.search(query, null, limit, captured)
                if (found.isNotEmpty()) note = "No ${kind.label} files match; showing every kind."
            }
            // The same reasoning one level down: the model pads a query with context ("hotel
            // confirmation MobiCom") and every word must match, so one word the file lacks
            // turns a hit into nothing. Loosen to any of the words, rank by how many match,
            // and say so — the model should know these are partial matches.
            if (found.isEmpty()) IndexPolicy.anyWordQuery(query)?.let { any ->
                found = index.search(any, kind, limit, captured)
                if (found.isEmpty() && kind != null) found = index.search(any, null, limit, captured)
                if (found.isNotEmpty()) note = "No file matches every word of \"$query\"; showing files that match some of them, best first."
            }
            // The similarity route runs beside the word route whenever photos are in
            // scope, not only when words found nothing: a query like "receipt" should list
            // the receipt.txt *and* the photo of one. It needs the switch on and the weights
            // present; without either, search is by words alone and nothing says otherwise
            // here — the status line's photo counts already tell the model what it has.
            val similar = if ((kind == null || kind == IndexPolicy.Kind.IMAGE) && AppSettings.labelPhotos) bySimilarity(query, limit, captured) else emptyList()
            SearchMerge.merge(found, similar, limit)
        } catch (e: Exception) {
            Log.w(FileIndexer.TAG, "search failed", e)
            return "search_files failed: ${e.message ?: e.javaClass.simpleName}. " + SearchRendering.describe(state)
        }
        coroutineContext[PhotoTurn]?.showCandidates(hits)
        val notes = listOfNotNull(captured?.description, note).joinToString("\n").takeIf { it.isNotEmpty() }
        return SearchRendering.render(query, hits, IndexState.read(context), notes)
    }

    private suspend fun bySimilarity(query: String, limit: Int, captured: CaptureDate.Filter?): List<FileIndex.Hit> {
        val model = ClipModel.get(context) ?: return emptyList()
        val vector = withContext(Dispatchers.Default) { model.embedText(IndexPolicy.similarityPrompt(query)) }
        return index.searchSimilar(vector, IndexPolicy.SIMILARITY_MIN, limit, captured)
    }

    private companion object {
        const val FIRST_BUILD_TIMEOUT_MS = 20_000L
    }
}
