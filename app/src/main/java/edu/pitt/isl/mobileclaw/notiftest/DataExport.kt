package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies the app database to a user-chosen destination through the Storage Access
 * Framework. The database is excluded from cloud backup and device transfer; export
 * is explicitly user-initiated from Settings and needs no storage permission.
 *
 * This must never be reachable from the agent: it is not a [Tool], is absent from
 * [ToolRegistry], and must not be routed through [PendingAction]. It exports the
 * complete audit trail, chat history, triage decisions and memory. Exposing it to the
 * tool loop would let a prompt injection request bulk extraction of sensitive data.
 *
 * The memory table can contain unredacted sensitive text attested by the user's own
 * words. [SettingsActivity] warns about that before exporting. The action log and
 * triage records are redacted before storage, but that does not make the complete
 * database free of personal data.
 */
object DataExport {

    private const val TAG = "MobileClaw-EX"

    const val MIME_TYPE = "application/octet-stream"

    /** Suggested filename for the SAF create-document dialog. */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "mobileclaw-export-$stamp.db"
    }

    /**
     * Writes a checkpointed copy of the database to [dest].
     *
     * Returns the number of bytes written, or a failure carrying the reason. The WAL
     * is folded in first via [MobileClawDb.checkpoint] — without that the copy would
     * be missing the newest rows.
     */
    suspend fun exportTo(context: Context, dest: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            MobileClawDb.checkpoint(context)

            val source = MobileClawDb.databaseFile(context)
            if (!source.exists()) {
                return@withContext Result.failure(IllegalStateException("No database to export yet."))
            }

            val written = context.contentResolver.openOutputStream(dest)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: return@withContext Result.failure(
                IllegalStateException("Couldn't open the destination for writing.")
            )

            Log.i(TAG, "Exported ${source.name} — $written bytes")
            Result.success(written)
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Result.failure(e)
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024        -> "%.1f KB".format(bytes / 1024.0)
        else                 -> "$bytes bytes"
    }
}
