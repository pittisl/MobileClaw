package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit's bundled text recogniser, Latin script: the words printed on a
 * photo, a screenshot or a rendered PDF page, for the index's OCR field.
 *
 * Same arrangement as [ImageLabels], for the same reasons: bundled so nothing is
 * downloaded at the booth and the emulator works, in-process so pixels never leave the
 * phone, and the same Firebase usage-telemetry caveat. Latin only — the system is
 * English-only by the English-only policy, and each extra script is another bundled model of several MB.
 *
 * What it reads is stored **unredacted** in the index, like a text file's `content`:
 * the index is private app storage, and the boundary is `SearchRendering`, which
 * redacts everything it hands the model. A boarding pass's record locator is in the
 * index so it can be searched for; it is scrubbed on the way out like any other.
 */
class TextReader : AutoCloseable {

    private val clientDelegate = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val client: TextRecognizer by clientDelegate

    /**
     * The recogniser's reading of [bitmap], in its own block order, or null when it found
     * nothing or failed. Not vetted here — [IndexPolicy.keepText] does that, once, for a
     * whole file's pages together.
     */
    suspend fun read(bitmap: Bitmap): String? {
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            client.process(image)
                .addOnSuccessListener { text -> cont.resume(text.text.takeIf { it.isNotBlank() }) }
                .addOnFailureListener { e ->
                    Log.w(FileIndexer.TAG, "text recognition failed: ${e.message}")
                    cont.resume(null)
                }
        }
    }

    override fun close() {
        if (clientDelegate.isInitialized()) client.close()
    }
}
