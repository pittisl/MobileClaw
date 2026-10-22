package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything the index derives from a file's pixels: the
 * classifier's labels and, when the CLIP weights are present, the picture's embedding —
 * for photos — and the text the recogniser read, for photos and PDFs alike. Any part may
 * be missing — labels when the classifier declined, the vector when the build has no
 * weights, the text when there was none — and the document records what it got.
 */
class PhotoFeatures(
    val labels: List<IndexPolicy.Label>,
    val embedding: FloatArray?,
    /**
     * Whether an analyser that embeds looked at this file. Distinct from `embedding != null`:
     * a file the decoder rejects under such an analyser has no vector and yet is finished
     * — without this flag it would be decoded again on every rebuild, which is the trap
     * the label side already paid for.
     */
    val embedded: Boolean,
    /** What the recogniser read, vetted by [IndexPolicy.keepText]; null when nothing was. */
    val text: String? = null,
    /**
     * Whether an analyser that reads text looked at this file — `embedded`'s twin, for the
     * same reason: a photo with no text in it is finished, not owed another OCR pass.
     */
    val read: Boolean = false,
)

/**
 * What the indexer asks of the models: the features for one image file, or null when
 * the file could not be read as an image. An interface so a test can drive
 * [FileIndexer]'s budget and carry-over rules with a fake, and so the indexer's caller
 * can pass none at all — the tool's bounded first build does — without the indexer
 * knowing which models it is not running.
 */
interface PhotoAnalyser : AutoCloseable {
    /** Whether this analyser produces embeddings; decides if an old vector-less record is still complete. */
    val embeds: Boolean
    /** Whether this analyser reads text; decides if an old record without OCR is still complete. */
    val reads: Boolean get() = false
    /** Features for an image or a PDF ([IndexPolicy.analysesContent]); null when the file could not be read. */
    suspend fun analyse(file: File): PhotoFeatures?
}

/**
 * What a rebuild does about photo content — three answers, because "no models this
 * time" and "the user turned it off" must not be the same thing. The first keeps what
 * the index already knows and reports the rest as owed; the second drops it and owes
 * nothing, or the status line would keep promising analysis that will never come.
 */
sealed interface PhotoContent {
    /** Settings switch off: nothing written, nothing carried over, nothing pending. */
    object Off : PhotoContent

    /** Keep what the index has, analyse nothing now; unanalysed photos are pending. */
    object Deferred : PhotoContent

    /** Carry over what holds, analyse the rest with [analyser] up to the per-build budget. */
    class Analyse(val analyser: PhotoAnalyser) : PhotoContent

    companion object {
        /**
         * The worker's choice: the real models when the switch is on, [Off] otherwise.
         * CLIP is optional — a checkout without the weights still labels.
         */
        fun forWorker(context: Context, indexPhotos: Boolean): PhotoContent =
            if (indexPhotos) Analyse(OnDevicePhotoAnalyser(ImageLabels(), ClipModel.get(context), TextReader())) else Off

        /** The tool's bounded first build: never the models, but only "later" if the switch says so. */
        fun forFirstBuild(indexPhotos: Boolean): PhotoContent = if (indexPhotos) Deferred else Off
    }
}

/**
 * The bundled models on one decode. Decoding is the shared cost — a
 * 12 MP HEIC is 48 MB of ARGB if read whole — so the file is opened once, at the size the
 * recogniser needs when there is one, and the classifier and CLIP get a copy scaled down
 * to their own input sizes. Any model may be absent.
 *
 * A PDF takes the other path: its first pages are rendered and read, and that is all —
 * a classifier's "font, rectangle" and a vector for a page of print would be noise in the
 * photo routes. Its record still says `embedded` under an analyser that embeds, meaning
 * "finished as far as this analyser goes", or it would be redone on every rebuild.
 */
class OnDevicePhotoAnalyser(
    private val labels: ImageLabels?,
    private val clip: ClipModel?,
    private val text: TextReader? = null,
) : PhotoAnalyser {

    override val embeds: Boolean get() = clip != null
    override val reads: Boolean get() = text != null

    override suspend fun analyse(file: File): PhotoFeatures? =
        if (IndexPolicy.extensionOf(file.name) == "pdf") analysePdf(file) else analyseImage(file)

    private suspend fun analyseImage(file: File): PhotoFeatures? {
        val target = if (text != null) IndexPolicy.OCR_TARGET_PX else IndexPolicy.LABEL_TARGET_PX
        val bitmap = withContext(Dispatchers.IO) { ImageDecode.scaled(file, target) } ?: return null
        var small: Bitmap? = null
        try {
            val read = text?.read(bitmap)
            small = ImageDecode.downTo(bitmap, IndexPolicy.LABEL_TARGET_PX)
            val found = labels?.label(small) ?: emptyList()
            val vector = clip?.let { model ->
                try {
                    withContext(Dispatchers.Default) { model.embedImage(small) }
                } catch (e: Exception) {
                    Log.w(FileIndexer.TAG, "embedding ${file.name} failed: ${e.message}")
                    null
                }
            }
            return PhotoFeatures(found, vector, embedded = clip != null, text = IndexPolicy.keepText(read), read = text != null)
        } finally {
            if (small != null && small !== bitmap) small.recycle()
            bitmap.recycle()
        }
    }

    private suspend fun analysePdf(file: File): PhotoFeatures? {
        val reader = text ?: return PhotoFeatures(emptyList(), null, embedded = clip != null)
        val pages = StringBuilder()
        val opened = PdfPages.forEach(file, IndexPolicy.PDF_OCR_PAGES, IndexPolicy.OCR_TARGET_PX) { page ->
            reader.read(page)?.let { pages.append(it).append('\n') }
        }
        if (!opened) return null
        return PhotoFeatures(emptyList(), null, embedded = clip != null, text = IndexPolicy.keepText(pages.toString()), read = true)
    }

    override fun close() {
        labels?.close()
        text?.close()
        // The CLIP model is process-wide and stays loaded; the next rebuild wants it too.
    }
}

/** Rendering a PDF's pages to bitmaps the recogniser can take. */
object PdfPages {

    /**
     * Renders up to [maxPages] pages of [file], each with its long edge at [targetPx], on
     * white — `PdfRenderer` draws onto a transparent bitmap, and black text on transparent
     * reads as nothing at all once the alpha is dropped — and hands each to [block], one
     * at a time so only one page is ever in memory. False when the file could not be
     * opened: encrypted (`SecurityException`), truncated or not a PDF at all, which the
     * caller records as read-with-nothing rather than leaving it to be retried forever.
     */
    suspend fun forEach(file: File, maxPages: Int, targetPx: Int, block: suspend (Bitmap) -> Unit): Boolean {
        val renderer = try {
            withContext(Dispatchers.IO) { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }
        } catch (e: Throwable) {
            Log.w(FileIndexer.TAG, "pdf ${file.name} not opened: ${e.javaClass.simpleName}")
            return false
        }
        renderer.use { r ->
            for (i in 0 until minOf(r.pageCount, maxPages)) {
                val bitmap = try {
                    withContext(Dispatchers.IO) {
                        r.openPage(i).use { page ->
                            // Page size is in points; scale so the long edge lands on target.
                            val scale = targetPx.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // One bad page (or an OutOfMemoryError on a huge one) ends the document,
                    // keeping what the earlier pages gave.
                    Log.w(FileIndexer.TAG, "pdf ${file.name} page $i: ${e.javaClass.simpleName}")
                    break
                }
                try {
                    block(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }
        return true
    }
}

/** Reading an image file down to a bitmap the models can take, without reading it whole. */
object ImageDecode {

    /**
     * Decodes [file] with the largest power-of-two subsampling that keeps the long edge at
     * or above [targetPx], in ARGB_8888 (what the models take), rotated
     * upright per EXIF so a portrait phone photo is not analysed lying down. Null for
     * anything `BitmapFactory` does not recognise — a `.jpg` that is really a text file, a
     * truncated download — which the caller records as "analysed, nothing found" so the
     * same broken file is not decoded again on every rebuild.
     */
    fun scaled(file: File, targetPx: Int = IndexPolicy.LABEL_TARGET_PX): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / (sample * 2) >= targetPx) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.path, opts)?.let { upright(it, exifRotation(file)) }
        }
    } catch (e: Throwable) {
        // OutOfMemoryError included: one oversized image must not take the rebuild down.
        Log.w(FileIndexer.TAG, "decode ${file.name} failed: ${e.javaClass.simpleName}")
        null
    }

    /**
     * [bitmap] scaled down by the same power-of-two rule [scaled] applies, so a copy made
     * from the OCR-sized decode is the size a [targetPx] decode would have been. The
     * original when it is already small enough; the caller recycles both.
     */
    fun downTo(bitmap: Bitmap, targetPx: Int): Bitmap {
        var sample = 1
        val longEdge = maxOf(bitmap.width, bitmap.height)
        while (longEdge / (sample * 2) >= targetPx) sample *= 2
        if (sample == 1) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width / sample).coerceAtLeast(1), (bitmap.height / sample).coerceAtLeast(1), true)
    }

    private fun upright(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** The EXIF orientation as degrees. */
    private fun exifRotation(file: File): Int = try {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}
