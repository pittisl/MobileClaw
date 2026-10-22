package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What [NewMediaWorker.schedule] registers: one work waiting on MediaStore's external
 * `Files` collection, descendants included. Each claim is one whose opposite would make
 * "take a photo, ask about it" silently wait for the twelve-hour rebuild — a trigger on
 * `Images` misses the downloaded PDF, a trigger without descendants fires for nothing,
 * and a `REPLACE` at app start would re-arm (and lose a pending burst) on every launch.
 * Asserts against what the app itself armed in `MobileClawApp.onCreate`.
 */
@RunWith(AndroidJUnit4::class)
class NewMediaScheduleTest {

    private lateinit var context: Context
    private lateinit var wm: WorkManager

    private fun info(): WorkInfo? =
        wm.getWorkInfosForUniqueWork(NewMediaWorker.UNIQUE_NAME).get().singleOrNull { !it.state.isFinished }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        wm = WorkManager.getInstance(context)
        NewMediaWorker.schedule(context)
    }

    @Test
    fun oneWorkWaitsOnTheWholeExternalFilesCollection() {
        val work = info()
        assertNotNull("nothing armed under ${NewMediaWorker.UNIQUE_NAME}", work)
        assertEquals(WorkInfo.State.ENQUEUED, work!!.state)
        val triggers = work.constraints.contentUriTriggers
        assertEquals(1, triggers.size)
        val t = triggers.single()
        assertEquals(MediaStore.Files.getContentUri("external"), t.uri)
        assertTrue("descendants, or an item URI never matches", t.isTriggeredForDescendants)
    }

    @Test
    fun reschedulingLeavesTheArmedWorkAlone() {
        val before = info()!!
        NewMediaWorker.schedule(context)
        val after = info()!!
        assertEquals(before.id, after.id)
        assertEquals(before.generation, after.generation)
    }
}
