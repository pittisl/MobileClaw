package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit's bundled image-labelling model: the words for what is in a picture.
 *
 * The model runs in-process and never sends pixels anywhere — the point of choosing the
 * bundled flavour over the Play-Services one, along with not depending on a download at
 * the demo booth. What the SDK *does* add is Firebase transport for its own usage
 * telemetry, which is the one outbound thing this dependency brings; there is no
 * documented switch for it. Worth knowing if a phone is ever audited for connections.
 *
 * Takes a bitmap rather than a file so the decode is shared with the embedding model
 * ([OnDevicePhotoAnalyser]). The classifier is created on first use and kept for the
 * analyser's lifetime; it is cheap to hold and expensive to create per image.
 */
class ImageLabels : AutoCloseable {

    private val clientDelegate = lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(IndexPolicy.LABEL_MIN_CONFIDENCE).build()
        )
    }
    private val client: ImageLabeler by clientDelegate

    /** The vetted labels for [bitmap], or null when the classifier itself failed. */
    suspend fun label(bitmap: Bitmap): List<IndexPolicy.Label>? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val raw = suspendCancellableCoroutine<List<IndexPolicy.Label>?> { cont ->
            client.process(image)
                .addOnSuccessListener { labels ->
                    cont.resume(labels.map { IndexPolicy.Label(it.text, it.confidence) })
                }
                .addOnFailureListener { e ->
                    Log.w(FileIndexer.TAG, "labelling failed: ${e.message}")
                    cont.resume(null)
                }
        }
        return raw?.let { IndexPolicy.keepLabels(it) }
    }

    override fun close() {
        // Only tear down what was built; touching the lazy would create a client to close.
        if (clientDelegate.isInitialized()) client.close()
    }
}
