package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.ZoneId

/** Reads metadata only, after FileIndexer has already admitted the path through FsPolicy. */
internal class PhotoCaptureReader(private val context: Context) {
    fun read(file: File, zone: ZoneId = ZoneId.systemDefault()): CaptureDate.Value? {
        val exif = try {
            val tags = ExifInterface(file.path)
            CaptureDate.fromExif(
                tags.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                tags.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                zone,
            )
        } catch (_: Exception) { null }
        if (exif != null) return exif
        return try {
            @Suppress("DEPRECATION")
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DATE_TAKEN),
                "${MediaStore.Images.Media.DATA} = ?",
                arrayOf(file.canonicalPath), null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) CaptureDate.fromMediaStore(cursor.getLong(0)) else null
            }
        } catch (_: Exception) { null }
    }
}
