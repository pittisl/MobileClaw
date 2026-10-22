package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.EmbeddingVector
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.GetByDocumentIdRequest
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The on-device full-text index behind `search_files`, as a thin wrapper
 * over AppSearch's in-process `LocalStorage` backend.
 *
 * Why AppSearch and not an FTS table in the Room database: the tokenising, prefix
 * matching, relevance ranking and snippeting are the whole feature, and Room's FTS4
 * gives none of the last two. It also keeps the file index out of `MobileClawDb` — a
 * rebuild drops and rewrites tens of thousands of rows, which is exactly the write
 * pattern the schema-versioned, exported, backup-excluded database should not carry.
 * The index is derived data: losing it costs one rebuild, so it needs no migration
 * story of its own (a schema change bumps [SCHEMA_VERSION] and forces a rebuild).
 *
 * One document per file, always in namespace [NAMESPACE]. What is indexed and what is
 * merely stored is decided in [IndexPolicy]; this class only knows the shape.
 *
 * Sessions are opened per operation and closed after it. `LocalStorage` keeps the
 * Icing instance alive process-wide, so the cost is a handle, and the alternative — a
 * long-lived session shared between a tool call and a background worker — is a lock
 * nobody wants to reason about.
 */
class FileIndex(private val context: Context, private val databaseName: String = DEFAULT_DATABASE) {

    /**
     * Whether this is the index the app builds and searches, as opposed to a test's. Only
     * the default one reports into [IndexState] — a test indexing a fixture must not
     * make Settings report fixture counts as though they described the real storage.
     */
    val isDefault: Boolean get() = databaseName == DEFAULT_DATABASE

    /** One search hit, already reduced to what the tool renders. */
    data class Hit(
        val name: String,
        val path: String,
        val kind: IndexPolicy.Kind,
        val sizeBytes: Long,
        val modifiedMs: Long,
        /** The matching passage of the file's content, if the match was in the body. */
        val snippet: String?,
        /** What the classifier saw in an image; null when it has not been analysed. */
        val labels: List<IndexPolicy.Label>? = null,
        /** Cosine against the query's embedding, when the hit came through, or also scored on, the similarity route. */
        val similarity: Float? = null,
        /** What OCR read off the image or PDF, whole; null when it read nothing or never ran. */
        val ocrText: String? = null,
        /** The matching passage of [ocrText], when the query matched there. */
        val ocrSnippet: String? = null,
        /** Photo capture instant, never file mtime; null means unknown. */
        val capturedAtMs: Long? = null,
        /** Provenance, including the assumed device zone for EXIF without an offset. */
        val capturedTimeSource: String? = null,
    )

    /** What goes into one document. Built by [FileIndexer]; all fields already policy-vetted. */
    data class Entry(
        val canonicalPath: String,
        val name: String,
        val kind: IndexPolicy.Kind,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val pathTokens: String,
        val content: String?,
        /**
         * What the models saw, or null for "not analysed" — which is distinct from features
         * with no labels and no vector, "analysed and nothing came of it". The indexer needs
         * the difference to know which images still owe a run.
         */
        val features: PhotoFeatures? = null,
        val capturedAtMs: Long? = null,
        val capturedTimeSource: String? = null,
    )

    /** What a previous build recorded about an image, enough to decide whether it still holds. */
    data class Analysed(val sizeBytes: Long, val modifiedMs: Long, val features: PhotoFeatures)

    private suspend fun <T> withSession(block: (AppSearchSession) -> T): T = withContext(Dispatchers.IO) {
        val session = LocalStorage.createSearchSessionAsync(
            LocalStorage.SearchContext.Builder(context, databaseName).build()
        ).get()
        try {
            session.setSchemaAsync(
                SetSchemaRequest.Builder().addSchemas(SCHEMA).setVersion(SCHEMA_VERSION).setForceOverride(true).build()
            ).get()
            block(session)
        } finally {
            session.close()
        }
    }

    /** Drops every document. A rebuild starts here so files deleted since last time vanish. */
    suspend fun clear() = withSession { session ->
        session.removeAsync("", SearchSpec.Builder().addFilterNamespaces(NAMESPACE).build()).get()
    }

    /** Writes [entries], overwriting by id. Returns how many the backend accepted. */
    suspend fun put(entries: List<Entry>): Int = withSession { session ->
        var accepted = 0
        entries.chunked(PUT_BATCH).forEach { batch ->
            val docs = batch.map { e ->
                val b = GenericDocument.Builder<GenericDocument.Builder<*>>(NAMESPACE, IndexPolicy.documentId(e.canonicalPath), SCHEMA_TYPE)
                    .setCreationTimestampMillis(e.modifiedMs)
                    .setPropertyString(P_NAME, e.name)
                    .setPropertyString(P_NAME_TOKENS, IndexPolicy.nameTokens(e.name))
                    .setPropertyString(P_PATH, e.canonicalPath)
                    .setPropertyString(P_PATH_TOKENS, e.pathTokens)
                    .setPropertyString(P_KIND, e.kind.label)
                    .setPropertyString(P_CONTENT, e.content ?: "")
                    .setPropertyLong(P_SIZE, e.sizeBytes)
                    .setPropertyLong(P_MODIFIED, e.modifiedMs)
                    .setPropertyLong(P_ANALYSED, if (e.features != null) 1L else 0L)
                    .setPropertyLong(P_EMBEDDED, if (e.features?.embedded == true) 1L else 0L)
                e.features?.labels?.takeIf { it.isNotEmpty() }?.let { labels ->
                    b.setPropertyString(P_LABELS, *labels.map { it.name }.toTypedArray())
                    b.setPropertyDouble(P_LABEL_SCORES, *labels.map { it.confidence.toDouble() }.toDoubleArray())
                }
                e.features?.embedding?.let { b.setPropertyEmbedding(P_CLIP, EmbeddingVector(it, ClipModel.SIGNATURE)) }
                e.features?.text?.let { b.setPropertyString(P_OCR, it) }
                e.capturedAtMs?.let { b.setPropertyLong(P_CAPTURED, it) }
                e.capturedTimeSource?.let { b.setPropertyString(P_CAPTURE_SOURCE, it) }
                b.setPropertyLong(P_READ, if (e.features?.read == true) 1L else 0L)
                b.build()
            }
            val result = session.putAsync(PutDocumentsRequest.Builder().addGenericDocuments(docs).build()).get()
            accepted += result.successes.size
        }
        session.requestFlushAsync().get()
        accepted
    }

    /** Number of live documents — what "the index holds N files" reports. */
    suspend fun count(): Int = withSession { session -> session.getStorageInfoAsync().get().aliveDocumentsCount }

    /**
     * What the index already knows about the images at [canonicalPaths], keyed by path,
     * for those an earlier build analysed. This is what makes analysis a one-time cost
     * per photo rather than a per-rebuild one: the indexer reads it *before* [clear] and
     * carries the features over when the file is unchanged.
     */
    suspend fun previousFeatures(canonicalPaths: List<String>): Map<String, Analysed> = withSession { session ->
        val out = HashMap<String, Analysed>(canonicalPaths.size)
        canonicalPaths.chunked(GET_BATCH).forEach { batch ->
            val request = GetByDocumentIdRequest.Builder(NAMESPACE)
                .addIds(batch.map { IndexPolicy.documentId(it) })
                .addProjection(SCHEMA_TYPE, listOf(P_PATH, P_SIZE, P_MODIFIED, P_ANALYSED, P_EMBEDDED, P_LABELS, P_LABEL_SCORES, P_CLIP, P_OCR, P_READ))
                .build()
            session.getByDocumentIdAsync(request).get().successes.values.forEach { d ->
                if (d.getPropertyLong(P_ANALYSED) != 1L) return@forEach
                val path = d.getPropertyString(P_PATH) ?: return@forEach
                out[path] = Analysed(
                    d.getPropertyLong(P_SIZE), d.getPropertyLong(P_MODIFIED),
                    PhotoFeatures(
                        labelsOf(d), d.getPropertyEmbedding(P_CLIP)?.values, embedded = d.getPropertyLong(P_EMBEDDED) == 1L,
                        text = d.getPropertyString(P_OCR), read = d.getPropertyLong(P_READ) == 1L,
                    ),
                )
            }
        }
        out
    }

    /**
     * Photos whose embedding scores at least [minScore] cosine against [vector], best
     * first, with the score on each hit. The search is Icing's own: the query is
     * one `semanticSearch` call over the embedding property, ranked by the matched score,
     * so no vector ever leaves the index to be compared in Kotlin. [minScore] is the whole
     * "is there one" decision — see [IndexPolicy.SIMILARITY_MIN] for where it came from.
     */
    suspend fun searchSimilar(
        vector: FloatArray, minScore: Float, limit: Int, captured: CaptureDate.Filter? = null,
    ): List<Hit> = withSession { session ->
        val spec = SearchSpec.Builder()
            .addFilterNamespaces(NAMESPACE)
            .addEmbeddingParameters(EmbeddingVector(vector, ClipModel.SIGNATURE))
            .setDefaultEmbeddingSearchMetricType(SearchSpec.EMBEDDING_SEARCH_METRIC_TYPE_COSINE)
            .setListFilterQueryLanguageEnabled(true)
            .setRankingStrategy("sum(this.matchedSemanticScores(getEmbeddingParameter(0)))")
            .setResultCountPerPage(limit.coerceAtLeast(20))
            .build()
        val results = session.search("semanticSearch(getEmbeddingParameter(0), $minScore, 1, \"COSINE\")", spec)
        try {
            val hits = ArrayList<Hit>(limit)
            // Filter ranked pages before top-k. Inspecting only the first page loses a
            // valid yesterday photo whenever today's photos fill the leading results.
            while (hits.size < limit) {
                val page = results.nextPageAsync.get()
                if (page.isEmpty()) break
                for (r in page) {
                    val d = r.genericDocument
                    val capturedAt = capturedAt(d)
                    if (captured != null && !captured.includes(capturedAt)) continue
                    hits += Hit(
                        name = d.getPropertyString(P_NAME) ?: "",
                        path = d.getPropertyString(P_PATH) ?: "",
                        kind = IndexPolicy.Kind.parse(d.getPropertyString(P_KIND)) ?: IndexPolicy.Kind.OTHER,
                        sizeBytes = d.getPropertyLong(P_SIZE),
                        modifiedMs = d.getPropertyLong(P_MODIFIED),
                        snippet = null,
                        labels = if (d.getPropertyLong(P_ANALYSED) == 1L) labelsOf(d) else null,
                        similarity = r.rankingSignal.toFloat(),
                        ocrText = d.getPropertyString(P_OCR),
                        capturedAtMs = capturedAt,
                        capturedTimeSource = d.getPropertyString(P_CAPTURE_SOURCE),
                    )
                    if (hits.size >= limit) break
                }
            }
            hits
        } finally {
            results.close()
        }
    }

    /**
     * Relevance-ranked search. Kind and capture date filter each ranked page before
     * top-k; pagination continues until enough eligible hits are found or exhausted.
     * Neither the query language nor the first page can weaken a requested date.
     */
    suspend fun search(
        query: String, kind: IndexPolicy.Kind?, limit: Int, captured: CaptureDate.Filter? = null,
    ): List<Hit> = withSession { session ->
        val fetch = limit.coerceAtLeast(20)
        val spec = SearchSpec.Builder()
            .addFilterNamespaces(NAMESPACE)
            .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
            .setRankingStrategy(SearchSpec.RANKING_STRATEGY_RELEVANCE_SCORE)
            .setResultCountPerPage(fetch)
            .setSnippetCount(fetch)
            .setSnippetCountPerProperty(1)
            .setMaxSnippetSize(SNIPPET_CHARS)
            .build()
        val results = session.search(query, spec)
        try {
            val hits = ArrayList<Hit>(limit)
            while (hits.size < limit) {
                val page = results.nextPageAsync.get()
                if (page.isEmpty()) break
                for (r in page) {
                    val d = r.genericDocument
                    val k = IndexPolicy.Kind.parse(d.getPropertyString(P_KIND)) ?: IndexPolicy.Kind.OTHER
                    if (kind != null && k != kind) continue
                    val capturedAt = capturedAt(d)
                    if (captured != null && !captured.includes(capturedAt)) continue
                    val snippet = r.matchInfos.firstOrNull { it.propertyPath == P_CONTENT }?.snippet?.toString()
                    val ocrSnippet = r.matchInfos.firstOrNull { it.propertyPath == P_OCR }?.snippet?.toString()
                    hits += Hit(
                        name = d.getPropertyString(P_NAME) ?: "",
                        path = d.getPropertyString(P_PATH) ?: "",
                        kind = k,
                        sizeBytes = d.getPropertyLong(P_SIZE),
                        modifiedMs = d.getPropertyLong(P_MODIFIED),
                        snippet = snippet?.takeIf { it.isNotBlank() },
                        labels = if (d.getPropertyLong(P_ANALYSED) == 1L) labelsOf(d) else null,
                        ocrText = d.getPropertyString(P_OCR),
                        ocrSnippet = ocrSnippet?.takeIf { it.isNotBlank() },
                        capturedAtMs = capturedAt,
                        capturedTimeSource = d.getPropertyString(P_CAPTURE_SOURCE),
                    )
                    if (hits.size >= limit) break
                }
            }
            hits
        } finally {
            results.close()
        }
    }

    /** Missing is distinct from epoch zero; GenericDocument's scalar getter conflates them. */
    private fun capturedAt(document: GenericDocument): Long? = document.getPropertyLongArray(P_CAPTURED)?.firstOrNull()

    /** The two repeated properties zipped back into labels; a document with neither has an empty list. */
    private fun labelsOf(d: GenericDocument): List<IndexPolicy.Label> {
        val names = d.getPropertyStringArray(P_LABELS) ?: return emptyList()
        val scores = d.getPropertyDoubleArray(P_LABEL_SCORES) ?: DoubleArray(0)
        return names.mapIndexed { i, n -> IndexPolicy.Label(n, scores.getOrElse(i) { 0.0 }.toFloat()) }
    }

    companion object {
        const val DEFAULT_DATABASE = "mobileclaw-files"
        private const val NAMESPACE = "files"
        private const val SCHEMA_TYPE = "File"

        /**
         * Bump when the schema below changes; `setForceOverride` then drops the old index.
         * v2 added the label fields, v3 the embedding, v4 the OCR
         * text. v5 adds optional capture metadata: the old features can remain during
         * the rebuild, but IndexState's version gate ensures old photos get dates too.
         */
        const val SCHEMA_VERSION = 5
        private const val PUT_BATCH = 200
        private const val GET_BATCH = 500
        private const val SNIPPET_CHARS = 160

        private const val P_NAME = "name"
        private const val P_NAME_TOKENS = "nameTokens"
        private const val P_PATH = "path"
        private const val P_PATH_TOKENS = "pathTokens"
        private const val P_KIND = "kind"
        private const val P_CONTENT = "content"
        private const val P_SIZE = "size"
        private const val P_MODIFIED = "modified"
        private const val P_ANALYSED = "analysed"
        private const val P_LABELS = "labels"
        private const val P_LABEL_SCORES = "labelScores"
        private const val P_CLIP = "clip"
        private const val P_EMBEDDED = "embedded"
        private const val P_OCR = "ocr"
        private const val P_READ = "read"
        private const val P_CAPTURED = "capturedAt"
        private const val P_CAPTURE_SOURCE = "captureSource"

        private fun text(name: String, indexing: Int, cardinality: Int = AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL) =
            AppSearchSchema.StringPropertyConfig.Builder(name)
                .setCardinality(cardinality)
                .setIndexingType(indexing)
                .setTokenizerType(
                    if (indexing == AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                        AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE
                    else AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN
                )
                .build()

        private fun number(name: String) = AppSearchSchema.LongPropertyConfig.Builder(name)
            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
            .build()

        /**
         * Names and directory words are prefix-indexed ("receip" finds the receipt);
         * content is exact-term, which keeps the index a fraction of the size for a 64 kB
         * body and still matches whole words, which is how people search for text.
         * Labels are exact-term too: they are single words the model will type whole, and
         * so is OCR text, which is a body like `content` and searched the same way.
         * Their scores are stored beside them, unindexed, for the rendering. The embedding
         * is indexed for similarity, unquantised: 512 floats a photo is 2 kB, and the
         * 8-bit option would shave a store nobody has measured as too big.
         */
        private val SCHEMA: AppSearchSchema = AppSearchSchema.Builder(SCHEMA_TYPE)
            .addProperty(text(P_NAME, AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES))
            .addProperty(text(P_NAME_TOKENS, AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES))
            .addProperty(text(P_PATH, AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE))
            .addProperty(text(P_PATH_TOKENS, AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES))
            .addProperty(text(P_KIND, AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS))
            .addProperty(text(P_CONTENT, AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS))
            .addProperty(text(P_LABELS, AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS, AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED))
            .addProperty(text(P_OCR, AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS))
            .addProperty(
                AppSearchSchema.DoublePropertyConfig.Builder(P_LABEL_SCORES)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED).build()
            )
            .addProperty(
                AppSearchSchema.EmbeddingPropertyConfig.Builder(P_CLIP)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setIndexingType(AppSearchSchema.EmbeddingPropertyConfig.INDEXING_TYPE_SIMILARITY)
                    .build()
            )
            .addProperty(number(P_SIZE))
            .addProperty(number(P_MODIFIED))
            .addProperty(number(P_ANALYSED))
            .addProperty(number(P_EMBEDDED))
            .addProperty(number(P_READ))
            .addProperty(number(P_CAPTURED))
            .addProperty(text(P_CAPTURE_SOURCE, AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE))
            .build()
    }
}
