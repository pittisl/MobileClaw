package edu.pitt.isl.mobileclaw.notiftest.skills.messaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Bundle
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.SelectedPhoto
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** No contacts, SIM, SmsManager instance, or submit method is used in these tests. */
class PhotoMmsFilesTest {
    private lateinit var context: Context
    private lateinit var source: File
    private val drafts = mutableListOf<PhotoMmsFiles.Draft>()
    private val amy = PhotoMmsPolicy.Phone("fixture", "Amy (test only)", "+12025550198")

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        source = File(context.cacheDir, "photo-mms-test-${UUID.randomUUID()}.jpg")
    }

    @After fun cleanUp() {
        drafts.forEach { it.directory.deleteRecursively() }
        source.delete()
    }

    @Test fun privatePayloadRespectsCarrierBoundsAndProviderIsReadOnly() {
        val bitmap = Bitmap.createBitmap(2100, 1400, Bitmap.Config.ARGB_8888)
        // A nontrivial image exercises size reduction; a solid block fits regardless
        // of resolution and would not reveal an ignored carrier byte limit.
        val row = IntArray(bitmap.width)
        val random = java.util.Random(4)
        for (y in 0 until bitmap.height) {
            for (x in row.indices) row[x] = random.nextInt() or (0xff shl 24)
            bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val original = source.readBytes()
        val draft = prepare(config(100 * 1024, 640, 480))
        assertTrue(draft.pdu.length() <= 100 * 1024)
        val outgoing = BitmapFactory.decodeFile(draft.photo.path)
        assertTrue(outgoing.width <= 640 && outgoing.height <= 480)
        outgoing.recycle()
        assertArrayEquals(original, source.readBytes())
        assertTrue(draft.directory.canonicalPath.startsWith(context.filesDir.canonicalPath + "/photo-mms/"))
        assertEquals("prepared", PhotoMmsFiles.readRecord(draft.directory)!!.getString("state"))
        PhotoMmsFiles.verifyDraft(draft)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.mms", draft.pdu)
        assertEquals("application/vnd.wap.mms-message", context.contentResolver.getType(uri))
        assertArrayEquals(draft.pdu.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertThrows(Exception::class.java) { context.contentResolver.openFileDescriptor(uri, "rw")?.close() }
        draft.discard()
        assertFalse(draft.directory.exists())
    }

    @Test fun approvalSnapshotRejectsSameLengthSameMtimeReplacement() {
        val first = byteArrayOf(1, 2, 3, 4)
        source.writeBytes(first)
        val selection = selected()
        source.writeBytes(byteArrayOf(4, 3, 2, 1))
        source.setLastModified(selection.modifiedMs)
        assertThrows(IllegalArgumentException::class.java) { PhotoMmsFiles.verifiedOriginal(selection) }
    }

    @Test fun outgoingPreviewBakesExifRotationIntoPixels() {
        val bitmap = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xffcc5533.toInt())
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val draft = prepare(config())
        val decoded = BitmapFactory.decodeFile(draft.photo.path)
        assertEquals(80, decoded.width)
        assertEquals(40, decoded.height)
        decoded.recycle()
    }

    @Test fun invalidImageDoesNotLeavePreparedPayload() {
        source.writeText("Not an image")
        val before = PhotoMmsFiles.root(context).listFiles()?.map { it.name }?.toSet().orEmpty()
        assertThrows(IllegalArgumentException::class.java) { prepare(config()) }
        val after = PhotoMmsFiles.root(context).listFiles()?.map { it.name }?.toSet().orEmpty()
        assertEquals(before, after)
    }

    private fun prepare(config: Bundle) = PhotoMmsFiles.prepare(context, selected(), amy, "photo-mms-fixture", "", 1, config)
        .also { drafts += it }

    private fun selected() = SelectedPhoto(UUID.randomUUID().toString(), source.path, source.name,
        PhotoMmsFiles.sha256(source.readBytes()), source.length(), source.lastModified())

    private fun config(bytes: Int = 300 * 1024, width: Int = 1280, height: Int = 1280) = Bundle().apply {
        putInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, bytes)
        putInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH, width)
        putInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT, height)
    }
}
