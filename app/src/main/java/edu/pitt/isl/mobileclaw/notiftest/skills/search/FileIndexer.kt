package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Walks shared storage and rebuilds the [FileIndex], analysing images and PDFs
 * as it goes — labels, embeddings and the text OCR reads off them.
 *
 * The walk is always a full rebuild. The card changes under us in ways no single signal
 * reports — files copied over USB, an app clearing its export folder — and a rebuild of a
 * few thousand documents is seconds of I/O on a phone that is otherwise idle, which is
 * when the worker runs. The one exception is [add], for files MediaStore has just told
 * [NewMediaWorker] about: a photo taken a moment ago should be searchable now, and the
 * rebuild remains what reconciles everything else, deletions included.
 *
 * Photo features are the exception to "full rebuild": they are the one per-file cost
 * that is not a read, so they are carried across rebuilds for any image whose size and
 * mtime are unchanged, and only [IndexPolicy.MAX_NEW_LABELS_PER_BUILD] images are
 * analysed for the first time in one run. The rest are reported as pending and the
 * worker comes back for them. [photoContent] says which of that happens: the tool's
 * bounded first build defers analysis (one photo folder would blow its 20 s), and the
 * Settings switch turns the whole thing off — features dropped, nothing owed.
 *
 * The walk is the only thing in the app that reads user files in bulk, so it is the
 * place the policy has to hold: every path goes through [IndexPolicy.readDenial] (which
 * is [edu.pitt.isl.mobileclaw.notiftest.FsPolicy]) before it is opened, and a denied
 * subtree is logged and skipped, never quietly included. The models see only files that
 * passed the same check.
 */
class FileIndexer(
    private val context: Context,
    private val index: FileIndex = FileIndex(context),
    /** Overridable so a test can index a fixture directory instead of the card. */
    private val root: File = Environment.getExternalStorageDirectory(),
    /** Decided by the caller from the Settings switch; there is no default on purpose. */
    private val photoContent: PhotoContent,
) {

    data class Outcome(
        val filesIndexed: Int,
        val withContent: Int,
        val skippedDenied: Int,
        val durationMs: Long,
        val error: String?,
        /** Images in the index that carry features (new or carried over). */
        val imagesAnalysed: Int = 0,
        /** Images eligible for analysis that this build did not get to. */
        val imagesPending: Int = 0,
        /** The Settings switch was off: photos went in by name only, and nothing is owed. */
        val analysisOff: Boolean = false,
    )

    /**
     * Rebuilds the index from [root]. Never throws: the worker and the Settings button
     * both want an outcome to report, not an exception to classify.
     */
    suspend fun rebuild(): Outcome {
        val started = System.currentTimeMillis()
        val rootCanonical = try {
            root.canonicalPath
        } catch (e: IOException) {
            return finish(Outcome(0, 0, 0, 0, "root ${root.path} could not be resolved: ${e.message}"), started)
        }
        IndexPolicy.readDenial(rootCanonical)?.let { reason ->
            return finish(Outcome(0, 0, 0, 1, "root refused: $reason"), started)
        }
        if (!root.isDirectory) {
            return finish(Outcome(0, 0, 0, 0, "root ${root.path} is not a readable directory"), started)
        }

        val entries = ArrayList<FileIndex.Entry>(1024)
        /** Positions in [entries] of images that qualify for analysis, with the file to decode. */
        val candidates = ArrayList<Pair<Int, File>>()
        var withContent = 0
        var denied = 0
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty() && entries.size < IndexPolicy.MAX_FILES_PER_BUILD) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                val name = child.name
                if (child.isDirectory) {
                    if (IndexPolicy.skipsDirectory(name)) continue
                    val canonical = try { child.canonicalPath } catch (_: IOException) { continue }
                    // Symlinks out of the root (or back into it) are where a walk turns into
                    // a loop or an escape; only descend when the target still lives under root.
                    if (!canonical.startsWith(rootCanonical)) continue
                    if (IndexPolicy.readDenial(canonical) != null) { denied++; continue }
                    stack.add(child)
                    continue
                }
                if (!child.isFile || IndexPolicy.skipsFile(name)) continue
                val canonical = try { child.canonicalPath } catch (_: IOException) { continue }
                if (IndexPolicy.readDenial(canonical) != null) { denied++; continue }

                val entry = entryFor(child, canonical, rootCanonical)
                if (entry.content != null) withContent++
                if (IndexPolicy.analysesContent(name, entry.sizeBytes)) candidates += entries.size to child
                entries += entry
                if (entries.size >= IndexPolicy.MAX_FILES_PER_BUILD) break
            }
        }

        val off = photoContent is PhotoContent.Off
        val analysed = if (off) Analysed(0, 0) else try {
            analyseImages(entries, candidates)
        } catch (e: Exception) {
            // The read of the old index failed, or a model did. Names and text are still
            // worth writing; the images just go in unanalysed and are owed a run.
            Log.w(TAG, "analysis skipped", e)
            Analysed(0, candidates.size)
        } finally {
            (photoContent as? PhotoContent.Analyse)?.analyser?.close()
        }

        val outcome = try {
            index.clear()
            val accepted = index.put(entries)
            Outcome(accepted, withContent, denied, 0, null, analysed.done, analysed.pending, off)
        } catch (e: Exception) {
            Log.w(TAG, "index write failed", e)
            Outcome(0, 0, denied, 0, "index write failed: ${e.message}")
        }
        return finish(outcome, started)
    }

    /**
     * Indexes [files] into the existing index without a walk and without [FileIndex.clear]
     * — the path for a photo just taken or a PDF just downloaded, which should be
     * searchable now, not at the next twelve-hour rebuild ([NewMediaWorker]). Each file is
     * held to exactly what the walk would have held it to: under [root], no skipped or
     * hidden directory on the way down, not a hidden file, and [IndexPolicy.readDenial]
     * clear — so the incremental path cannot admit a file a rebuild would then drop.
     *
     * Same analysis, same carry-over, same per-build budget; a file that no longer exists
     * is simply not added (the next rebuild removes it). [IndexState] is left alone: it
     * describes the last full rebuild, and a count patched up here would drift from it.
     */
    suspend fun add(files: List<File>): Outcome {
        val started = System.currentTimeMillis()
        val rootCanonical = try { root.canonicalPath } catch (e: IOException) {
            return Outcome(0, 0, 0, 0, "root ${root.path} could not be resolved: ${e.message}")
        }
        val entries = ArrayList<FileIndex.Entry>(files.size)
        val candidates = ArrayList<Pair<Int, File>>()
        var withContent = 0
        var denied = 0
        for (file in files.distinctBy { it.path }.take(IndexPolicy.MAX_FILES_PER_ADD)) {
            if (!file.isFile) continue
            val canonical = try { file.canonicalPath } catch (_: IOException) { continue }
            if (!IndexPolicy.admitsUnderRoot(canonical, rootCanonical)) { denied++; continue }
            val entry = entryFor(file, canonical, rootCanonical)
            if (entry.content != null) withContent++
            if (IndexPolicy.analysesContent(entry.name, entry.sizeBytes)) candidates += entries.size to file
            entries += entry
        }
        if (entries.isEmpty()) {
            (photoContent as? PhotoContent.Analyse)?.analyser?.close()
            return Outcome(0, 0, denied, System.currentTimeMillis() - started, null)
        }
        val off = photoContent is PhotoContent.Off
        val analysed = if (off) Analysed(0, 0) else try {
            analyseImages(entries, candidates)
        } catch (e: Exception) {
            Log.w(TAG, "analysis skipped", e)
            Analysed(0, candidates.size)
        } finally {
            (photoContent as? PhotoContent.Analyse)?.analyser?.close()
        }
        val outcome = try {
            Outcome(index.put(entries), withContent, denied, 0, null, analysed.done, analysed.pending, off)
        } catch (e: Exception) {
            Log.w(TAG, "index write failed", e)
            Outcome(0, 0, denied, 0, "index write failed: ${e.message}")
        }
        val done = outcome.copy(durationMs = System.currentTimeMillis() - started)
        Log.i(TAG, "add: ${done.filesIndexed} of ${files.size} files (${done.imagesAnalysed} analysed, ${done.imagesPending} pending, " +
            "${done.skippedDenied} refused) in ${done.durationMs} ms")
        return done
    }

    /** One file as the index records it; the walk and [add] build entries the same way. */
    private fun entryFor(file: File, canonical: String, rootCanonical: String): FileIndex.Entry {
        val name = file.name
        val size = file.length()
        val kind = IndexPolicy.kindOf(name)
        val capture = if (kind == IndexPolicy.Kind.IMAGE) PhotoCaptureReader(context).read(file) else null
        return FileIndex.Entry(
            canonicalPath = canonical,
            name = name,
            kind = kind,
            sizeBytes = size,
            modifiedMs = file.lastModified(),
            pathTokens = IndexPolicy.pathTokens(canonical, rootCanonical),
            content = if (IndexPolicy.indexesContent(name, size)) readContent(file) else null,
            capturedAtMs = capture?.atMs,
            capturedTimeSource = capture?.source,
        )
    }

    private class Analysed(val done: Int, val pending: Int)

    /**
     * Fills in `features` on the image entries: carried over from the previous build
     * where the file is unchanged, computed now for up to the per-build budget, and left
     * null — pending — beyond it or when there is no analyser this time.
     */
    private suspend fun analyseImages(entries: ArrayList<FileIndex.Entry>, candidates: List<Pair<Int, File>>): Analysed {
        if (candidates.isEmpty()) return Analysed(0, 0)
        val analyser = (photoContent as? PhotoContent.Analyse)?.analyser
        val previous = index.previousFeatures(candidates.map { entries[it.first].canonicalPath })
        var done = 0
        var pending = 0
        var fresh = 0
        for ((i, file) in candidates) {
            val e = entries[i]
            val old = previous[e.canonicalPath]
            // Unchanged file, and a record as complete as this analyser could make it: a
            // labels-only record from a build without the CLIP weights is redone once the
            // weights arrive, and only then — and likewise one made before OCR existed.
            val complete = old != null &&
                (old.features.embedded || analyser?.embeds != true) &&
                (old.features.read || analyser?.reads != true)
            if (old != null && complete && old.sizeBytes == e.sizeBytes && old.modifiedMs == e.modifiedMs) {
                entries[i] = e.copy(features = old.features)
                done++
                continue
            }
            if (analyser == null || fresh >= IndexPolicy.MAX_NEW_LABELS_PER_BUILD) { pending++; continue }
            fresh++
            // A file the decoder rejects is recorded as analysed-with-nothing, not left
            // pending: pending means "come back", and coming back to the same broken file
            // on every rebuild would spend the budget on it forever.
            entries[i] = e.copy(
                features = analyser.analyse(file) ?: PhotoFeatures(emptyList(), null, embedded = analyser.embeds, read = analyser.reads)
            )
            done++
        }
        return Analysed(done, pending)
    }

    private fun readContent(file: File): String? = try {
        // Bounded read: the cap is on characters kept, but a 4 MB log should not be
        // decoded whole to keep its first 64 k. Read a little more than the cap in bytes
        // (multi-byte text) and let normalisation trim it.
        val cap = IndexPolicy.MAX_CONTENT_CHARS * 2
        val buf = ByteArray(cap)
        var read = 0
        file.inputStream().use { input ->
            while (read < cap) {
                val n = input.read(buf, read, cap - read)
                if (n < 0) break
                read += n
            }
        }
        IndexPolicy.normaliseContent(String(buf, 0, read, Charsets.UTF_8)).takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        null
    }

    private fun finish(outcome: Outcome, started: Long): Outcome {
        val done = outcome.copy(durationMs = System.currentTimeMillis() - started)
        if (index.isDefault) IndexState.record(context, done)
        if (done.error != null) Log.w(TAG, "rebuild: ${done.error}")
        else Log.i(
            TAG,
            "rebuild: ${done.filesIndexed} files (${done.withContent} with content, ${done.imagesAnalysed} images analysed, " +
                "${done.imagesPending} pending${if (done.analysisOff) ", analysis off" else ""}, ${done.skippedDenied} denied) in ${done.durationMs} ms"
        )
        return done
    }

    companion object {
        const val TAG = "MobileClaw-FS"
    }
}

/**
 * What the last rebuild did, in `SharedPreferences` so the tool and the Settings row can
 * say "3 412 files, built 20 minutes ago" without opening the index. Written only by
 * [FileIndexer.finish]; the index itself remains the source of truth for what is in it.
 */
object IndexState {
    private const val PREFS = "search_index"
    private const val K_BUILT_AT = "built_at"
    private const val K_FILES = "files"
    private const val K_CONTENT = "with_content"
    private const val K_DENIED = "denied"
    private const val K_DURATION = "duration_ms"
    private const val K_ERROR = "error"
    private const val K_ANALYSED = "images_analysed"
    private const val K_PENDING = "images_pending"
    private const val K_ANALYSIS_OFF = "analysis_off"
    private const val K_SCHEMA = "schema_version"

    data class Snapshot(
        val builtAtMs: Long,
        val files: Int,
        val withContent: Int,
        val denied: Int,
        val durationMs: Long,
        val error: String?,
        val imagesAnalysed: Int = 0,
        val imagesPending: Int = 0,
        val analysisOff: Boolean = false,
        val schemaVersion: Int = FileIndex.SCHEMA_VERSION,
    ) {
        val hasBuilt get() = builtAtMs > 0

        /**
         * True when there is nothing usable yet — never built, or the last attempt found no
         * access. Both are the state a user who has *just* granted access is in, and the
         * periodic job will not look again for hours, so callers that see this and hold the
         * grant should start a rebuild rather than wait.
         */
        val needsBuild get() = !hasBuilt || error == NO_ACCESS || schemaVersion != FileIndex.SCHEMA_VERSION
    }

    /** The error [SearchIndexWorker] records when it declines for lack of the grant. */
    const val NO_ACCESS = "all-files access not granted"

    fun record(context: Context, o: FileIndexer.Outcome) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(K_BUILT_AT, System.currentTimeMillis())
            .putInt(K_FILES, o.filesIndexed)
            .putInt(K_CONTENT, o.withContent)
            .putInt(K_DENIED, o.skippedDenied)
            .putLong(K_DURATION, o.durationMs)
            .putString(K_ERROR, o.error)
            .putInt(K_ANALYSED, o.imagesAnalysed)
            .putInt(K_PENDING, o.imagesPending)
            .putBoolean(K_ANALYSIS_OFF, o.analysisOff)
            .putInt(K_SCHEMA, if (o.error == null) FileIndex.SCHEMA_VERSION else 0)
            .apply()
    }

    fun read(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            p.getLong(K_BUILT_AT, 0L), p.getInt(K_FILES, 0), p.getInt(K_CONTENT, 0),
            p.getInt(K_DENIED, 0), p.getLong(K_DURATION, 0L), p.getString(K_ERROR, null),
            p.getInt(K_ANALYSED, 0), p.getInt(K_PENDING, 0), p.getBoolean(K_ANALYSIS_OFF, false),
            p.getInt(K_SCHEMA, 0),
        )
    }
}
