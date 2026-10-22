package edu.pitt.isl.mobileclaw.notiftest.skills.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * MobileCLIP-S0 on ONNX Runtime maps images and text into a shared embedding space
 * for similarity search.
 *
 * Weights are optional assets fetched by `tools/fetch_clip_model.sh` into `assets/clip/`.
 * The runtime uses file paths, so the assets are copied into app-private storage on
 * first use. [get] returns null when weights are absent or cannot load; callers then
 * skip the similarity route and retain word-based search.
 *
 * The configured exports use fp32 vision weights (45 MB) and int8 text weights
 * (43 MB). Their dimensions, preprocessing and tokenizer must remain compatible.
 * One instance is shared per process to avoid repeatedly opening sessions and mapping
 * the weights for the worker and search tool.
 */
class ClipModel private constructor(
    private val env: OrtEnvironment,
    private val vision: OrtSession,
    private val text: OrtSession,
    private val tokenizer: ClipTokenizer,
) {

    /** L2-normalised image embedding of [bitmap], any size; the model's own preprocessing is applied here. */
    fun embedImage(bitmap: Bitmap): FloatArray {
        val input = preprocess(bitmap)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, INPUT_PX.toLong(), INPUT_PX.toLong())).use { tensor ->
            vision.run(mapOf(vision.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return normalise((result[0].value as Array<FloatArray>)[0])
            }
        }
    }

    /** L2-normalised text embedding of [sentence]. */
    fun embedText(sentence: String): FloatArray {
        val ids = tokenizer.encode(sentence)
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { tensor ->
            text.run(mapOf(text.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return normalise((result[0].value as Array<FloatArray>)[0])
            }
        }
    }

    /**
     * The export's `preprocessor_config.json`: shortest edge to 256 (bilinear), centre
     * crop 256×256, RGB scaled to [0, 1], **no** mean/std normalisation — MobileCLIP was
     * trained without it, and applying CLIP's usual constants here would shift every
     * embedding. Planar (NCHW) float, which is what the graph's `pixel_values` expects.
     */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val scale = INPUT_PX.toFloat() / minOf(bitmap.width, bitmap.height)
        val w = maxOf(INPUT_PX, (bitmap.width * scale).roundToInt())
        val h = maxOf(INPUT_PX, (bitmap.height * scale).roundToInt())
        val scaled = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            val left = (w - INPUT_PX) / 2
            val top = (h - INPUT_PX) / 2
            val pixels = IntArray(INPUT_PX * INPUT_PX)
            scaled.getPixels(pixels, 0, INPUT_PX, left, top, INPUT_PX, INPUT_PX)
            val plane = INPUT_PX * INPUT_PX
            val out = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = pixels[i]
                out[i] = ((p shr 16) and 0xff) / 255f
                out[plane + i] = ((p shr 8) and 0xff) / 255f
                out[2 * plane + i] = (p and 0xff) / 255f
            }
            return out
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    companion object {
        const val DIMS = 512
        const val INPUT_PX = 256

        /** Names the embedding property's model, so a vector from a different export is never compared with these. */
        const val SIGNATURE = "mobileclip-s0-xenova-onnx"

        private const val TAG = FileIndexer.TAG
        private const val ASSET_DIR = "clip"
        private val ASSETS = listOf("vision.onnx", "text.onnx", "tokenizer.json")

        @Volatile private var shared: ClipModel? = null
        @Volatile private var failed = false

        /** True when the build carries the weights; cheap, and the question to ask before promising similarity. */
        fun isAvailable(context: Context): Boolean =
            try { context.assets.list(ASSET_DIR)?.toSet()?.containsAll(ASSETS) == true } catch (_: Exception) { false }

        /**
         * The process-wide model, loaded on first call; null when unavailable or when
         * loading failed once (a runtime that could not load its native library will not
         * load it on the next call either, and retrying per photo would only spend time).
         */
        @Synchronized
        fun get(context: Context): ClipModel? {
            shared?.let { return it }
            if (failed || !isAvailable(context)) return null
            return try {
                val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
                val files = materialise(context, dir)
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions()
                val vision = env.createSession(files.getValue("vision.onnx").path, opts)
                val text = env.createSession(files.getValue("text.onnx").path, opts)
                val tokenizer = ClipTokenizer(files.getValue("tokenizer.json").readText())
                ClipModel(env, vision, text, tokenizer).also { shared = it; Log.i(TAG, "CLIP model loaded") }
            } catch (e: Throwable) {
                // UnsatisfiedLinkError included: an ABI the runtime was not packaged for.
                failed = true
                Log.w(TAG, "CLIP model unavailable: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

        /**
         * Copies the assets out once per installed build. AAPT compresses them (which is
         * fine — it is 10 % off the APK — but rules out `openFd` and a file-descriptor
         * check), so "is the copy current" is answered by the package's own update time
         * stamped beside the files, not by their sizes.
         */
        private fun materialise(context: Context, dir: File): Map<String, File> {
            val stamp = File(dir, ".installed")
            val installed = try {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            } catch (_: Exception) { "unknown" }
            val current = stamp.takeIf { it.exists() }?.readText() == installed && ASSETS.all { File(dir, it).length() > 0 }
            if (!current) {
                for (name in ASSETS) {
                    context.assets.open("$ASSET_DIR/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
                }
                stamp.writeText(installed)
            }
            return ASSETS.associateWith { File(dir, it) }
        }

        fun normalise(v: FloatArray): FloatArray {
            var sum = 0f
            for (x in v) sum += x * x
            val n = sqrt(sum)
            return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
