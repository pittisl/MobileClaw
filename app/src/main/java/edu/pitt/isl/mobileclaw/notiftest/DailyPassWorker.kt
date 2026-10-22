package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * What actually wakes the daily pass up.
 *
 * `WorkManager` schedules the pass independently of Activity launches, so it can run
 * when the user leaves the app closed. Its persistent schedule survives process restarts.
 *
 * The work is **unique and periodic**, keyed by [UNIQUE_NAME] with [ExistingPeriodicWorkPolicy.KEEP]:
 * [MobileClawApp] runs on every process start, so normal scheduling leaves the existing
 * request unchanged. The one migration from a network-constrained request uses `UPDATE`
 * with its original timing: WorkManager preserves enqueue time, and we preserve its
 * interval, flex and initial delay rather than recalculating a new first-run time.
 *
 * ### Why almost every outcome is `success`
 *
 * [Result.retry] is for work that a later attempt could complete. A pass that declined
 * because the agent is paused, because there is no API key, or because the day was already
 * summarised would decline again in fifteen minutes for exactly the same reason — the next
 * *period* is the right cadence for re-asking, not the backoff. Only a transient failure
 * (network, 5xx) retries, and that is decided by the same [AgentError] classification the
 * chat and triage paths use rather than by a second opinion invented here.
 */
class DailyPassWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // The day that has just finished, resolved at run time rather than at enqueue time:
        // this request was scheduled once and may fire for months.
        val dayKey = DailyPass.previousDayKey(System.currentTimeMillis())

        return when (val outcome = LlmClients.dailyPass(applicationContext).run(dayKey)) {
            is DailyPassRunner.Outcome.Applied -> {
                Log.i(TAG, "consolidated $dayKey: ${outcome.result}")
                Result.success()
            }
            is DailyPassRunner.Outcome.Failed ->
                if (outcome.retryable) {
                    Log.w(TAG, "consolidating $dayKey failed, will retry: ${outcome.message}")
                    Result.retry()
                } else {
                    // A key the provider refuses, or a request it rejects, produces the
                    // same answer on every attempt. Retrying it spends battery to relearn
                    // something the run-state displays already report.
                    Log.w(TAG, "consolidating $dayKey failed permanently: ${outcome.message}")
                    Result.success()
                }
            else -> {
                Log.i(TAG, "no pass for $dayKey: ${outcome::class.simpleName}")
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "MobileClaw-DP"
        private val scheduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val scheduleMutex = Mutex()

        /**
         * `internal` rather than `private` so `DailyPassScheduleTest` can ask `WorkManager`
         * about this exact work. The name is the identity of the schedule — a test that
         * hard-coded its own copy would keep passing after a rename, while the app quietly
         * enqueued a second, parallel pass.
         */
        @VisibleForTesting
        internal const val UNIQUE_NAME = "mobileclaw-daily-memory-pass"

        /**
         * Local time the pass aims for. Late enough that the day it summarises is well
         * over, early enough that the result is there before the user picks the phone up.
         * `WorkManager` will move it to suit Doze and battery; the hour is a preference,
         * not a promise, which is the right trade for a job whose output nobody is waiting
         * on.
         */
        private const val RUN_AT_HOUR = 3

        /**
         * Enqueued from [MobileClawApp.onCreate] — asynchronous and idempotent, and it has to happen
         * on a process start rather than from an Activity, because the pass must keep
         * running for a user who leaves the app closed for a week. The returned job lets
         * instrumentation wait for the database operation without blocking app startup.
         */
        fun schedule(context: Context): Job {
            val appContext = context.applicationContext
            return scheduleScope.launch {
                try {
                    // Serialize check + enqueue so simultaneous callers cannot both migrate
                    // the same generation. The database is the migration marker; no separate
                    // preference can get out of sync after a process death.
                    scheduleMutex.withLock {
                        val wm = WorkManager.getInstance(appContext)
                        val existing = wm.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).first()
                            .firstOrNull { !it.state.isFinished }
                        val migration = existing?.takeIf {
                            it.periodicityInfo != null &&
                                it.constraints.requiredNetworkType != NetworkType.NOT_REQUIRED
                        }
                        val periodicity = migration?.periodicityInfo
                        val day = TimeUnit.DAYS.toMillis(1)
                        val request = PeriodicWorkRequestBuilder<DailyPassWorker>(
                            periodicity?.repeatIntervalMillis ?: day, TimeUnit.MILLISECONDS,
                            periodicity?.flexIntervalMillis ?: day, TimeUnit.MILLISECONDS
                        )
                            .setInitialDelay(migration?.initialDelayMillis ?:
                                DailyPass.msUntilNextHour(System.currentTimeMillis(), RUN_AT_HOUR),
                                TimeUnit.MILLISECONDS)
                            // A loopback model can work offline. Cloud failures already use
                            // bounded retry; keep every other existing constraint unchanged.
                            .setConstraints((migration?.constraints?.let { Constraints.Builder(it) }
                                ?: Constraints.Builder())
                                .setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                            .apply { migration?.tags?.forEach { addTag(it) } }
                            .build()
                        wm.enqueueUniquePeriodicWork(UNIQUE_NAME,
                            if (migration != null) ExistingPeriodicWorkPolicy.UPDATE
                            else ExistingPeriodicWorkPolicy.KEEP, request).await()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed registration can be retried at the next process start.
                    Log.w(TAG, "Daily memory scheduling could not be updated")
                }
            }
        }

        /**
         * Runs the pass now, for the last complete day. **Test and debug affordance only** —
         * there is no user-facing "consolidate now", because a button that spends an LLM
         * call and rewrites memory is not something to put next to a settings toggle.
         */
        suspend fun runNow(context: Context): DailyPassRunner.Outcome =
            LlmClients.dailyPass(context).run(DailyPass.previousDayKey(System.currentTimeMillis()))
    }
}
