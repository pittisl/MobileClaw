package edu.pitt.isl.mobileclaw.notiftest.skills.messaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.telephony.SmsManager
import edu.pitt.isl.mobileclaw.notiftest.SelectedPhoto
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Private, bounded, frozen payloads: approval always names the bytes actually sent. */
internal object PhotoMmsFiles {
    data class Draft(
        val id: String,
        val directory: File,
        val photo: File,
        val pdu: File,
        val createdMs: Long,
        val pduHash: String,
        val photoHash: String,
        val subscriptionId: Int,
    ) {
        fun discard() {
            // Once dispatch started Android may still be reading the URI. A dialog's
            // dismissal must never delete a payload that has entered the telephony service.
            if (readRecord(directory)?.optString("state") == "prepared") directory.deleteRecursively()
        }
    }

    fun prepare(context: Context, selected: SelectedPhoto, recipient: PhotoMmsPolicy.Phone,
                sessionId: String, body: String, subscriptionId: Int, config: Bundle): Draft {
        prune(context)
        val original = verifiedOriginal(selected)
        val maxBytes = config.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 300 * 1024)
            .takeIf { it > 0 }?.coerceAtMost(1024 * 1024) ?: (300 * 1024)
        require(maxBytes >= 24 * 1024) { "The carrier's MMS size limit is too small for a photo." }
        val maxWidth = config.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH, 1280)
            .takeIf { it > 0 }?.coerceAtMost(2048) ?: 1280
        val maxHeight = config.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT, 1280)
            .takeIf { it > 0 }?.coerceAtMost(2048) ?: 1280
        val jpeg = compress(original, maxWidth, maxHeight, maxBytes - 16 * 1024)
        val id = UUID.randomUUID().toString()
        val encoded = PhotoMmsPdu.encode(recipient.number, jpeg, body, "mc-$id")
        require(encoded.size <= maxBytes) { "The complete message exceeds the carrier's MMS limit." }
        val directory = File(root(context), id).apply { check(mkdirs()) }
        try {
            val photo = File(directory, "photo.jpg").apply { writeBytes(jpeg); setReadOnly() }
            val pdu = File(directory, "message.pdu").apply { writeBytes(encoded); setReadOnly() }
            val created = System.currentTimeMillis()
            val record = JSONObject()
                .put("id", id).put("sessionId", sessionId).put("createdMs", created)
                .put("contactId", recipient.contactId).put("name", recipient.name).put("number", recipient.number)
                .put("subscriptionId", subscriptionId).put("state", "prepared")
                .put("pduSha256", sha256(encoded)).put("photoSha256", sha256(jpeg))
            writeRecord(directory, record)
            return Draft(id, directory, photo, pdu, created, sha256(encoded), sha256(jpeg), subscriptionId)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun verifiedOriginal(selected: SelectedPhoto): ByteArray {
        val file = File(selected.path)
        require(file.isFile && file.length() in 1..MAX_SOURCE_BYTES && file.length() == selected.sizeBytes &&
            file.lastModified() == selected.modifiedMs) { "The selected photo changed or is no longer readable. Open it again." }
        val bytes = file.inputStream().use { it.readBytesBounded(MAX_SOURCE_BYTES.toInt()) }
        require(bytes.size.toLong() == selected.sizeBytes && sha256(bytes) == selected.sha256) {
            "The selected photo changed since you opened it. Open it again before sending."
        }
        return bytes
    }

    fun verifyDraft(draft: Draft) {
        require(draft.pdu.isFile && draft.photo.isFile &&
            sha256(draft.pdu.readBytes()) == draft.pduHash && sha256(draft.photo.readBytes()) == draft.photoHash) {
            "The prepared photo message changed. Prepare it again."
        }
    }

    private fun compress(source: ByteArray, maxWidth: Int, maxHeight: Int, byteLimit: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        require(bounds.outWidth in 1..65535 && bounds.outHeight in 1..65535) { "The selected file is not a supported photo." }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > maxWidth * 2 || bounds.outHeight / inSampleSize > maxHeight * 2)
                inSampleSize *= 2
        }
        var bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options)
            ?: throw IllegalArgumentException("The selected image could not be decoded.")
        try {
            val orientation = runCatching {
                ExifInterface(ByteArrayInputStream(source)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            if (!transform.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            val ratio = min(1f, min(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height))
            if (ratio < 1f) {
                val resized = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * ratio).toInt()), max(1, (bitmap.height * ratio).toInt()), true)
                if (resized !== bitmap) { bitmap.recycle(); bitmap = resized }
            }
            repeat(8) {
                for (quality in intArrayOf(90, 80, 70, 60, 50, 40)) {
                    val output = ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Unable to encode the photo." }
                    if (output.size() <= byteLimit) return output.toByteArray()
                }
                require(bitmap.width > 64 && bitmap.height > 64) { "The photo cannot fit the carrier's MMS limit." }
                val smaller = Bitmap.createScaledBitmap(bitmap, max(1, bitmap.width * 3 / 4), max(1, bitmap.height * 3 / 4), true)
                bitmap.recycle(); bitmap = smaller
            }
            throw IllegalArgumentException("The photo cannot fit the carrier's MMS limit.")
        } finally {
            bitmap.recycle()
        }
    }

    fun root(context: Context) = File(context.filesDir, "photo-mms")
    fun directory(context: Context, id: String): File? =
        id.takeIf { Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(it) }?.let { File(root(context), it) }

    fun readRecord(directory: File): JSONObject? = runCatching { JSONObject(File(directory, "record.json").readText()) }.getOrNull()

    fun writeRecord(directory: File, record: JSONObject) {
        val temporary = File(directory, "record.json.tmp").apply { writeText(record.toString()) }
        check(temporary.renameTo(File(directory, "record.json"))) { "Unable to persist MMS state." }
    }

    private fun prune(context: Context) {
        val now = System.currentTimeMillis()
        root(context).listFiles()?.forEach { directory ->
            val record = readRecord(directory)
            val created = record?.optLong("createdMs") ?: directory.lastModified()
            // Submitted messages get a day for Android's delayed retry/callback. Old
            // preparations only need the five-minute human-confirmation lifetime.
            val ttl = if (record?.optString("state") == "prepared") PhotoMmsPolicy.APPROVAL_TTL_MS else 24 * 60 * 60 * 1000L
            if (now - created > ttl) directory.deleteRecursively()
        }
    }

    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val size = read(buffer)
            if (size < 0) break
            require(result.size() + size <= limit) { "Photo exceeds the 30 MB input limit." }
            result.write(buffer, 0, size)
        }
        return result.toByteArray()
    }

    private const val MAX_SOURCE_BYTES = 30 * 1024 * 1024L
}
