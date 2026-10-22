package edu.pitt.isl.mobileclaw.notiftest

import edu.pitt.isl.mobileclaw.notiftest.skills.search.FileIndex
import edu.pitt.isl.mobileclaw.notiftest.skills.search.IndexPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Local UI data from actual search hits, never parsed back out of model prose. */
data class PhotoCandidate(
    val sessionId: String,
    val path: String,
    val name: String,
    val capturedAtMs: Long? = null,
    val generation: Long = PhotoSelectionStore.generation(sessionId)
)

/** A user selection, including a fingerprint so replacing the file cannot change consent. */
data class SelectedPhoto(
    val id: String,
    val path: String,
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val capturedAtMs: Long? = null,
    val generation: Long = 0
)

/** Fingerprint of the exact source bytes decoded for the user's preview. */
data class PhotoFingerprint(val path: String, val sha256: String, val sizeBytes: Long, val modifiedMs: Long)

/** Decode [bytes], then retain only [fingerprint] while the preview dialog is open. */
data class PhotoPreviewSnapshot(val bytes: ByteArray, val fingerprint: PhotoFingerprint)

/**
 * Ephemeral selection, not a memory or a permission. Process death requires the user
 * to choose again; a restored transcript cannot restore authority to share a file.
 */
object PhotoSelectionStore {
    private val selections = mutableMapOf<String, SelectedPhoto>()
    private val generations = mutableMapOf<String, Long>()
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    @Synchronized fun selected(sessionId: String): SelectedPhoto? = selections[sessionId]
    @Synchronized fun generation(sessionId: String): Long = generations[sessionId] ?: 0
    @Synchronized fun isCurrent(sessionId: String, selected: SelectedPhoto): Boolean =
        selections[sessionId]?.id == selected.id && generation(sessionId) == selected.generation

    @Synchronized fun clear(sessionId: String) {
        selections.remove(sessionId)
        generations[sessionId] = generation(sessionId) + 1
        _changes.value++
    }

    /** Revoke a selection/approval without invalidating the still-visible search results. */
    @Synchronized fun deselect(sessionId: String) {
        selections.remove(sessionId)
        _changes.value++
    }

    /** One bounded read supplies both the displayed pixels and their consent fingerprint. */
    suspend fun preview(candidate: PhotoCandidate): PhotoPreviewSnapshot = withContext(Dispatchers.IO) {
        requireCurrentCandidate(candidate)
        readSnapshot(candidate.path).also { requireCurrentCandidate(candidate) }
    }

    /**
     * Production UI passes its preview fingerprint; a changed file must be reopened.
     * The default supports non-UI callers/tests which select a fresh snapshot directly.
     * Neither this method nor preview is exposed as an agent tool.
     */
    suspend fun select(candidate: PhotoCandidate, previewFingerprint: PhotoFingerprint? = null): SelectedPhoto = withContext(Dispatchers.IO) {
        requireCurrentCandidate(candidate)
        val fingerprint = readSnapshot(candidate.path).fingerprint
        require(previewFingerprint == null || fingerprint == previewFingerprint) {
            "Photo changed since the preview. Open it again before selecting."
        }
        val selected = SelectedPhoto(
            UUID.randomUUID().toString(), fingerprint.path, File(fingerprint.path).name,
            fingerprint.sha256, fingerprint.sizeBytes, fingerprint.modifiedMs,
            candidate.capturedAtMs, candidate.generation
        )
        synchronized(this@PhotoSelectionStore) {
            requireCurrentCandidate(candidate)
            selections[candidate.sessionId] = selected
            _changes.value++
        }
        selected
    }

    @Synchronized private fun requireCurrentCandidate(candidate: PhotoCandidate) {
        require(ChatStore.activeSessionId.value == candidate.sessionId &&
            generation(candidate.sessionId) == candidate.generation) {
            "This search is no longer current. Search for the photo again."
        }
    }

    private fun readSnapshot(path: String): PhotoPreviewSnapshot {
        val file = sharedImage(path)
        val size = file.length()
        val modified = file.lastModified()
        require(size in 1..MAX_SOURCE_BYTES) { "This photo exceeds the 30 MiB attachment limit or is empty." }
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream(size.toInt())
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_SOURCE_BYTES) { "Photo grew beyond the 30 MiB attachment limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size.toLong() == size && file.length() == size && file.lastModified() == modified) {
            "Photo changed while loading. Open it again."
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return PhotoPreviewSnapshot(bytes, PhotoFingerprint(file.path, hash, size, modified))
    }

    fun sharedImage(path: String): File {
        val allowed = FsPolicy.vet(path, FsPolicy.Access.READ) as? FsPolicy.Verdict.Allow
            ?: throw IllegalArgumentException("Photo is outside shared storage.")
        val file = File(allowed.canonical)
        require(listOf("/storage/emulated/0/", "/sdcard/", "/storage/self/primary/").any { file.path.startsWith(it) }) {
            "Photo is outside shared storage."
        }
        require(file.isFile && file.canRead() && file.extension.lowercase() in IMAGE_EXTENSIONS) {
            "Photo is unavailable or is not a supported image."
        }
        return file
    }

    private const val MAX_SOURCE_BYTES = 30L * 1024 * 1024
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")
}

/**
 * Captured at foreground turn start, and carried across dispatcher changes to tools.
 * Reading the currently visible session inside a tool would race the chat switcher.
 */
class PhotoTurn(
    val sessionId: String,
    val selectedPhoto: SelectedPhoto? = PhotoSelectionStore.selected(sessionId)
) : AbstractCoroutineContextElement(PhotoTurn) {
    companion object Key : CoroutineContext.Key<PhotoTurn>

    private val startGeneration = PhotoSelectionStore.generation(sessionId)
    @Volatile var candidates: List<PhotoCandidate> = emptyList()
        private set

    fun showCandidates(hits: List<FileIndex.Hit>) {
        // A cleared chat must not regain candidates from an old, still-finishing turn.
        if (PhotoSelectionStore.generation(sessionId) != startGeneration) return
        candidates = hits.filter { it.kind == IndexPolicy.Kind.IMAGE }.take(20).map {
            PhotoCandidate(sessionId, it.path, it.name, it.capturedAtMs, startGeneration)
        }
    }

    fun modelContext(): String = selectedPhoto?.let {
        "The user explicitly selected a photo in this conversation: " +
            "selection ID ${it.id}. " +
            "'that pic' refers to this exact local selection. Use send_photo for a requested phone image message. " +
            "Selection does not authorize sending; the app must show its image/recipient confirmation."
    } ?: "No photo is selected in this conversation. After photo search, the user must preview a candidate " +
        "and tap Use this photo before send_photo can prepare it. Never guess which result 'that pic' means."
}
