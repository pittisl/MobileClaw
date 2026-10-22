package edu.pitt.isl.mobileclaw.notiftest.skills.search

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import edu.pitt.isl.mobileclaw.notiftest.AppSettings
import edu.pitt.isl.mobileclaw.notiftest.Capability
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Adds recently created or modified media to the search index, including photos,
 * screenshots and downloaded documents.
 *
 * [SearchIndexWorker]'s periodic full walk reconciles the complete file set and
 * deletions. This worker uses [FileIndexer.add] with the same admission rules.
 * Indexing new media does not itself start an agent turn.
 *
 * A one-time WorkManager request watches the external MediaStore `Files` collection
 * through a content-URI trigger. Each run appends its successor because the trigger
 * fires once. Triggered URI shapes vary, and changes during a run may not get another
 * signal, so the worker queries MediaStore for files added or modified since the
 * previous run began rather than relying only on the trigger's URI list.
 */
class NewMediaWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // A recheck (below) is not the trigger work and must not append another one.
        if (!inputData.getBoolean(K_RECHECK, false)) arm(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        if (!Capability.ALL_FILES_ACCESS.isGranted(applicationContext)) return Result.success()
        // No full index yet (a freshly set-up phone): adding one file to nothing would leave
        // the status saying "never built" and the tool rebuilding inline without analysis.
        // A new file is as good a reason as any to build the whole thing now, with OCR.
        if (IndexState.read(applicationContext).needsBuild) {
            SearchIndexWorker.runNow(applicationContext)
            return Result.success()
        }

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis() / 1000
        val since = prefs.getLong(K_SEEN_S, now - FIRST_LOOKBACK_S) - CLOCK_SLACK_S
        val recent = recentFiles(applicationContext, since)
        AppSettings.init(applicationContext)
        if (recent.files.isNotEmpty()) {
            FileIndexer(applicationContext, photoContent = PhotoContent.forWorker(applicationContext, AppSettings.labelPhotos))
                .add(recent.files)
        }
        Log.i(TAG, "new media: ${recent.files.size} file(s), ${if (recent.pendingSince != null) "some still being written" else "none pending"}")
        // A camera can insert a pending row before finishing the file, while this run is
        // active and the next trigger is not armed. Keep the high-water mark behind pending
        // rows and schedule a short recheck so they do not wait for another media change.
        val seen = recent.pendingSince?.let { minOf(it, now) } ?: now
        prefs.edit().putLong(K_SEEN_S, seen).apply()
        if (recent.pendingSince != null) recheckSoon(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = FileIndexer.TAG

        @VisibleForTesting
        internal const val UNIQUE_NAME = "mobileclaw-file-index-new-media"

        @VisibleForTesting
        internal const val RECHECK_NAME = "mobileclaw-file-index-new-media-recheck"

        private const val PREFS = "search_index"
        private const val K_SEEN_S = "new_media_seen_s"
        private const val K_RECHECK = "recheck"
        private const val RECHECK_DELAY_S = 3L

        /** With no record of a last run, look back this far: enough for "the photo I just took". */
        private const val FIRST_LOOKBACK_S = 10 * 60L

        /** MediaStore's dates are whole seconds and written after the file; overlap a little. */
        private const val CLOCK_SLACK_S = 2L

        /**
         * Short on purpose: the visitor takes a photo and asks about it. The update delay
         * folds a burst (a camera writes the file, then its metadata) into one run; the max
         * delay bounds how long a steady trickle can postpone it.
         */
        private const val UPDATE_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 5_000L

        /** Comes back for files that were still being written; one queued at a time. */
        private fun recheckSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<NewMediaWorker>()
                .setInitialDelay(RECHECK_DELAY_S, TimeUnit.SECONDS)
                .setInputData(workDataOf(K_RECHECK to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(RECHECK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Arms the trigger; `KEEP` from app start so an armed work is left alone. The price:
         * a build that changes the trigger takes effect only after the work an older build
         * armed fires once — every run re-arms with the current build's constraints, so it
         * heals at the first new file (seen on `mobileclaw-34` after a mutation run).
         */
        fun schedule(context: Context) = arm(context, ExistingWorkPolicy.KEEP)

        private fun arm(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<NewMediaWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
                        .setTriggerContentUpdateDelay(UPDATE_DELAY_MS, TimeUnit.MILLISECONDS)
                        .setTriggerContentMaxDelay(MAX_DELAY_MS, TimeUnit.MILLISECONDS)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, policy, request)
        }

        /** What changed since a time: finished files to index, and the oldest one still being written. */
        internal class Recent(val files: List<File>, val pendingSince: Long?)

        /**
         * Files MediaStore says were added or modified at or after [sinceSeconds], newest
         * first, as paths. Added *or* modified: a file copied in keeps its old mtime but
         * gets a new `date_added`. Readable through `DATA` because the caller holds the
         * all-files grant; anything that is not a regular file is dropped. On API 30+ rows
         * still pending (a camera mid-write) are asked for too, and reported rather than
         * indexed — a half-written JPEG would be analysed as broken and never retried.
         */
        @VisibleForTesting
        internal fun recentFiles(context: Context, sinceSeconds: Long): Recent {
            val out = ArrayList<File>()
            var pendingSince: Long? = null
            val arg = sinceSeconds.toString()
            val where = "${MediaStore.MediaColumns.DATE_ADDED} >= ? OR ${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"
            val order = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            try {
                val uri = MediaStore.Files.getContentUri("external")
                val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.contentResolver.query(
                        uri,
                        arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DATE_ADDED),
                        Bundle().apply {
                            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
                            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(arg, arg))
                            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                        },
                        null,
                    )
                } else {
                    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), where, arrayOf(arg, arg), order)
                }
                cursor?.use { c ->
                    while (c.moveToNext() && out.size < IndexPolicy.MAX_FILES_PER_ADD) {
                        if (c.columnCount > 1 && c.getInt(1) == 1) {
                            val added = c.getLong(2)
                            pendingSince = pendingSince?.let { minOf(it, added) } ?: added
                            continue
                        }
                        c.getString(0)?.let { File(it) }?.takeIf { it.isFile }?.let { out += it }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore query failed: ${e.message}")
            }
            return Recent(out, pendingSince)
        }
    }
}
