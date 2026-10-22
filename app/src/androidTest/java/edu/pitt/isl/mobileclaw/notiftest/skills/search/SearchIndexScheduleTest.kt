package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * What [SearchIndexWorker.schedule] registers — the same four claims
 * `DailyPassScheduleTest` makes about the memory pass, for the same reason: each field is
 * one whose default or opposite would make the index silently never build. Asserts against
 * the schedule the app registered from `MobileClawApp.onCreate`; re-calling `schedule()` is
 * a no-op under `KEEP`, which is one of the claims.
 */
@RunWith(AndroidJUnit4::class)
class SearchIndexScheduleTest {

    private lateinit var context: Context
    private lateinit var wm: WorkManager

    private fun info(): WorkInfo? =
        wm.getWorkInfosForUniqueWork(SearchIndexWorker.UNIQUE_NAME).get().singleOrNull()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        wm = WorkManager.getInstance(context)
        SearchIndexWorker.schedule(context)
    }

    @Test
    fun theScheduleIsOneEnqueuedPeriodicItem() {
        val work = info()
        assertNotNull("no work under ${SearchIndexWorker.UNIQUE_NAME}", work)
        assertTrue(work!!.state == WorkInfo.State.ENQUEUED || work.state == WorkInfo.State.RUNNING)
        assertEquals(TimeUnit.HOURS.toMillis(12), work.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun itDoesNotWaitForACharger() {
        // A demo phone that is never plugged in would otherwise never index.
        val c = info()!!.constraints
        assertFalse(c.requiresCharging())
        assertTrue(c.requiresBatteryNotLow())
        assertTrue(c.requiresStorageNotLow())
    }

    @Test
    fun reschedulingKeepsTheExistingWork() {
        // `generation`, not id: since WorkManager 2.9 `UPDATE` amends in place and keeps the
        // id, so an id-based assertion passes under both policies (see DailyPassScheduleTest).
        val before = info()!!.generation
        SearchIndexWorker.schedule(context)
        assertEquals(before, info()!!.generation)
    }
}
