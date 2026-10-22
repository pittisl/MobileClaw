package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What [NewMediaWorker.recentFiles] makes of a photo a camera is still writing (API 30+):
 * reported as pending, not handed to the indexer, and listed once it is finished. The
 * worker's hold-and-recheck is built on exactly this answer — a pending row that came back
 * as "nothing new" is how a photo taken during a run would wait for the next one.
 *
 * The row is inserted by the test itself, the way a camera app does it (`IS_PENDING = 1`,
 * write, then `IS_PENDING = 0`); an app needs no permission for its own media, and the
 * query reads `DATA` for its own rows.
 */
@RunWith(AndroidJUnit4::class)
class NewMediaQueryTest {

    private lateinit var context: Context
    private var uri: Uri? = null

    @Before
    fun setUp() {
        assumeTrue("IS_PENDING / MATCH_PENDING need API 30", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        uri?.let { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun aPhotoStillBeingWrittenIsReportedNotIndexedThenListedWhenDone() {
        val since = System.currentTimeMillis() / 1000 - 2
        val name = "IMG_test_pending_${System.nanoTime()}.jpg"
        uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MobileClawTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )
        assertNotNull("MediaStore refused the insert", uri)
        context.contentResolver.openOutputStream(uri!!)!!.use { it.write(ByteArray(2048) { 0x55 }) }

        val during = NewMediaWorker.recentFiles(context, since)
        assertTrue("a pending photo must not reach the indexer", during.files.none { it.name == name })
        assertNotNull("…but must be reported, or the high-water mark moves past it", during.pendingSince)

        context.contentResolver.update(uri!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val after = NewMediaWorker.recentFiles(context, since)
        assertTrue("the finished photo is listed", after.files.any { it.name == name })
        assertNull(after.pendingSince)
    }
}
