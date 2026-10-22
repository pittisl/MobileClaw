package edu.pitt.isl.mobileclaw.notiftest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import android.widget.ImageView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/** Bounded, local-only decoding; scroll/rebind must not put an old image into a new card. */
object PhotoImages {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun load(view: ImageView, path: String, edge: Int = 480) {
        val key = "$path:${File(path).lastModified()}:$edge"
        view.tag = key
        view.setImageDrawable(null)
        cache.get(key)?.let { view.setImageBitmap(it); return }
        view.post {
            val owner = view.findViewTreeLifecycleOwner() ?: return@post
            owner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) { runCatching { decode(path, edge) }.getOrNull() }
                if (bitmap != null) cache.put(key, bitmap)
                if (view.tag == key) view.setImageBitmap(bitmap)
            }
        }
    }

    fun decode(path: String, edge: Int): Bitmap? {
        require(edge > 0) { "Preview edge must be positive." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge * 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        return orient(bitmap, orientation)
    }

    /** Decode and orient the same immutable snapshot that the selection fingerprint covers. */
    fun decode(bytes: ByteArray, edge: Int): Bitmap? {
        require(edge > 0) { "Preview edge must be positive." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge * 2) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching {
            ByteArrayInputStream(bytes).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        }.getOrDefault(1)
        return orient(bitmap, orientation)
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> { setRotate(180f); postScale(-1f, 1f) }
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}
