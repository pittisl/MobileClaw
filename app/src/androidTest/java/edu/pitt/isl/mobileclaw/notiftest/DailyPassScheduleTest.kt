package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Verifies the periodic request [DailyPassWorker.schedule] registers with WorkManager.
 * These assertions cover registration and timing parameters, not whether Android
 * actually executes the worker after a reboot; that needs a separate device check.
 *
 * Instrumented because WorkManager requires a real Context and database. The tests
 * inspect the request already registered by MobileClawApp.onCreate. Scheduling again
 * must preserve it under KEEP. The migration case restores a network constraint on
 * that request and verifies its removal without moving the scheduled period.
 */
@RunWith(AndroidJUnit4::class)
class DailyPassScheduleTest {

    private lateinit var context: Context
    private lateinit var wm: WorkManager

    private fun info(): WorkInfo? =
        wm.getWorkInfosForUniqueWork(DailyPassWorker.UNIQUE_NAME).get().singleOrNull()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        wm = WorkManager.getInstance(context)
        runBlocking { DailyPassWorker.schedule(context).join() }
    }

    /**
     * One work item under the unique name, waiting to run. `singleOrNull` is the assertion:
     * two rows here would mean two passes a day, each spending an LLM call and each editing
     * the same memory rows unattended.
     */
    @Test
    fun theScheduleIsOneEnqueuedWorkItem() {
        val work = info()
        assertNotNull("no work enqueued under ${DailyPassWorker.UNIQUE_NAME}", work)
        assertEquals(WorkInfo.State.ENQUEUED, work!!.state)
    }

    /**
     * A day, not the 15-minute floor a mis-built `PeriodicWorkRequest` falls back to. The
     * pass reads a day of triage decisions and writes that day's summary, so a shorter
     * period is not "more often" — it is the same LLM call repeated against work already
     * done, 96 times a day.
     */
    @Test
    fun itRepeatsDaily() {
        val periodicity = info()!!.periodicityInfo
        assertNotNull("not a periodic request", periodicity)
        assertEquals(TimeUnit.DAYS.toMillis(1), periodicity!!.repeatIntervalMillis)
    }

    /**
     * A local model can run offline, so neither network connectivity nor charging gates
     * this pass. Cloud connection failures still use the worker's bounded retry path.
     */
    @Test
    fun itRequiresNeitherNetworkNorACharger() {
        val constraints = info()!!.constraints
        assertEquals(NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
        assertFalse("charging must not gate the pass", constraints.requiresCharging())
    }

    /**
     * The one that guards a real regression rather than a typo. [DailyPassWorker.schedule]
     * runs on **every process start**, and `MobileClawApp` is created often — a notification
     * can start the process. Normal scheduling must keep the request unchanged. `UPDATE`
     * preserves enqueue time, but substituting a freshly calculated initial delay would
     * still change a pending first run. Only the network migration may update the request.
     *
     * **`generation`, not `id`** — since WorkManager 2.9, `UPDATE` amends the work *in place*
     * and keeps its id, so an id-based check passes under both policies. `generation` is
     * the field `UPDATE` bumps and `KEEP` leaves alone, and it is the only thing in the
     * public API that separates them.
     */
    @Test
    fun reschedulingKeepsTheExistingWorkRatherThanRestartingItsPeriod() {
        val before = info()!!

        runBlocking { DailyPassWorker.schedule(context).join() }

        assertEquals(
            "schedule() amended the pending work — KEEP became UPDATE?",
            before.generation,
            info()!!.generation
        )
        assertEquals(before.nextScheduleTimeMillis, info()!!.nextScheduleTimeMillis)
    }

    @Test
    fun legacyNetworkConstraintIsMigratedOnceWithoutMovingItsPeriod() {
        val original = info()!!
        val periodicity = original.periodicityInfo!!
        val legacy = PeriodicWorkRequestBuilder<DailyPassWorker>(
            periodicity.repeatIntervalMillis, TimeUnit.MILLISECONDS,
            periodicity.flexIntervalMillis, TimeUnit.MILLISECONDS
        )
            .setInitialDelay(original.initialDelayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder(original.constraints)
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .apply { original.tags.forEach { addTag(it) } }
            .build()
        wm.enqueueUniquePeriodicWork(DailyPassWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE, legacy).result.get()
        val before = info()!!
        assertEquals(NetworkType.CONNECTED, before.constraints.requiredNetworkType)

        try {
            // Two concurrent startup calls must still apply the migration only once.
            runBlocking {
                joinAll(DailyPassWorker.schedule(context), DailyPassWorker.schedule(context))
            }
            val migrated = info()!!
            assertEquals(NetworkType.NOT_REQUIRED, migrated.constraints.requiredNetworkType)
            assertEquals(before.id, migrated.id)
            assertEquals(before.generation + 1, migrated.generation)
            assertEquals(before.periodicityInfo, migrated.periodicityInfo)
            assertEquals(before.initialDelayMillis, migrated.initialDelayMillis)
            assertEquals(before.nextScheduleTimeMillis, migrated.nextScheduleTimeMillis)
            assertEquals(before.tags, migrated.tags)

            runBlocking { DailyPassWorker.schedule(context).join() }
            assertEquals(migrated.generation, info()!!.generation)
            assertEquals(migrated.nextScheduleTimeMillis, info()!!.nextScheduleTimeMillis)
        } finally {
            // Keep the app's real request usable even if an assertion fails.
            val restored = PeriodicWorkRequestBuilder<DailyPassWorker>(
                periodicity.repeatIntervalMillis, TimeUnit.MILLISECONDS,
                periodicity.flexIntervalMillis, TimeUnit.MILLISECONDS
            )
                .setInitialDelay(original.initialDelayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(original.constraints)
                .apply { original.tags.forEach { addTag(it) } }
                .build()
            if (info()?.constraints != original.constraints) {
                wm.enqueueUniquePeriodicWork(DailyPassWorker.UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE, restored).result.get()
            }
        }
    }
}
