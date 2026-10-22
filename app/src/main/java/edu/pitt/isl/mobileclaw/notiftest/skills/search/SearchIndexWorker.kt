package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.pitt.isl.mobileclaw.notiftest.AppSettings
import java.util.concurrent.TimeUnit

/**
 * Keeps the file index fresh through scheduled background rebuilds.
 *
 * Indexing happens *here*, with one exception: the very first build, which
 * `SearchFilesTool` runs inline (bounded) when it finds access granted and nothing
 * indexed, because the periodic job ran before the grant and a "started, ask again"
 * answer is one the model does not follow up on. Every later build is this worker's;
 * a tool call never walks the card again on every turn.
 *
 * Unique periodic work under [UNIQUE_NAME] with `KEEP` preserves the existing request
 * when `MobileClawApp` enqueues on process start. Constraints are battery-not-low and
 * storage-not-low; indexing does not require the phone to be charging.
 */
class SearchIndexWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!edu.pitt.isl.mobileclaw.notiftest.Capability.ALL_FILES_ACCESS.isGranted(applicationContext)) {
            // Not a retry: the grant is a Settings toggle the user has to flip, and the
            // next period is the right time to look again. The tool reports the missing
            // capability on its own, so nothing is lost by declining quietly here.
            Log.i(TAG, "skipped: ${IndexState.NO_ACCESS}")
            // Record the missing grant so the tool and Settings can request a first
            // build after access is granted, without waiting for the next period.
            if (!IndexState.read(applicationContext).hasBuilt) {
                IndexState.record(applicationContext, FileIndexer.Outcome(0, 0, 0, 0, IndexState.NO_ACCESS))
            }
            return Result.success()
        }
        // The worker is the one caller that decides labelling from the switch; init is
        // idempotent and this process may be one WorkManager started cold.
        AppSettings.init(applicationContext)
        val outcome = FileIndexer(applicationContext, photoContent = PhotoContent.forWorker(applicationContext, AppSettings.labelPhotos)).rebuild()
        // Labelling is budgeted per build (IndexPolicy.MAX_NEW_LABELS_PER_BUILD); a card
        // with more new photos than that is finished over several runs, each asked for
        // here rather than left to the next period twelve hours away. A distinct unique
        // name, because this run may itself be the KEEP-guarded "now" work, which would
        // swallow a request under its own name while it is still running.
        if (outcome.error == null && outcome.imagesPending > 0) continueSoon(applicationContext)
        return if (outcome.error == null) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = FileIndexer.TAG

        @VisibleForTesting
        internal const val UNIQUE_NAME = "mobileclaw-file-index"

        @VisibleForTesting
        internal const val NOW_NAME = "mobileclaw-file-index-now"

        @VisibleForTesting
        internal const val CONTINUE_NAME = "mobileclaw-file-index-continue"

        /** Twice a day is plenty for a card that changes by the day; the "now" path covers the rest. */
        private const val PERIOD_HOURS = 12L

        private const val CONTINUE_DELAY_MINUTES = 2L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SearchIndexWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /**
         * Rebuild as soon as WorkManager can run it — the Settings row, and what a user who
         * just granted access or just copied files over wants. `KEEP` so tapping twice
         * queues one rebuild, not two.
         */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SearchIndexWorker>().build()
            )
        }

        /**
         * Another rebuild shortly, to label the images the last one ran out of budget for.
         * Same constraints as the periodic job — the point of the budget is that this work
         * is not free — and a short delay so back-to-back runs do not pin the CPU.
         */
        fun continueSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<SearchIndexWorker>()
                .setInitialDelay(CONTINUE_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CONTINUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
