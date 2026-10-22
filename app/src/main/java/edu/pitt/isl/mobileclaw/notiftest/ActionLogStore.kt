package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Persistent audit trail of every action the agent triggers — a notification button
 * click, an inline reply, an SMS, a calendar write, or a proposal that was dropped
 * or rejected.
 *
 * This is the one store that genuinely has to outlive the process. It is the record
 * of what the agent *did to the user's device*, and with
 * [AppSettings.fullAutomation] on those actions can run unattended, so an in-memory
 * log would lose exactly the events nobody watched happen.
 *
 * Backed by Room ([ActionLogDao]); the public shape is unchanged from when it was a
 * plain `MutableStateFlow`, so callers are unaffected. [entries] is fed by a DAO
 * [kotlinx.coroutines.flow.Flow] that Room re-emits on every write.
 *
 * Supplied text is redacted via [ReasoningModule.redact] before it is stored — the
 * database is app-private but it is also what [DataExport] hands to the user, so
 * nothing sensitive should be in it in the first place.
 *
 * [init] must run once with a context before anything is written; [MobileClawApp]
 * does this at process start. Records made before then are buffered and flushed on
 * init rather than dropped.
 */
object ActionLogStore {

    private const val TAG = "MobileClaw-AL"

    /**
     * How many rows the UI-facing [entries] flow carries. Matches the old in-memory cap,
     * and is deliberately far below [MAX_ROWS] — this is a live flow held in memory, not
     * the record itself. Public so [ActionLogActivity] can say which of the two numbers
     * it is showing instead of presenting a truncated list as the whole trail.
     */
    const val OBSERVED_ENTRIES = 200

    /** Hard row ceiling; the audit trail is small, so this is generous. */
    private const val MAX_ROWS = 5_000

    private val RETENTION_MS = TimeUnit.DAYS.toMillis(90)

    /** Rows inserted between retention sweeps — sweeping on every insert is wasteful. */
    private const val PURGE_INTERVAL = 50

    data class ActionLogEntry(
        val timeMs: Long,
        val appName: String,
        val key: String,
        val actionTitle: String,
        /** Already redacted; null when the action carried no inline text. */
        val suppliedText: String?,
        val result: String,
        val success: Boolean
    ) {
        fun toLogLine(): String {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timeMs))
            val verb = if (success) "CLICK-OK" else "CLICK-FAIL"
            val textPart = if (suppliedText != null) " text=\"$suppliedText\"" else ""
            return "$verb $stamp $appName :: \"$actionTitle\"$textPart → $result"
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var dao: ActionLogDao? = null

    /** Records made before [init]; flushed to the DB in order once the DAO exists. */
    private val preInit = mutableListOf<ActionLogEntry>()

    private var insertsSincePurge = 0

    private val _entries = MutableStateFlow<List<ActionLogEntry>>(emptyList())

    /** The newest [OBSERVED_ENTRIES] rows, oldest first. */
    val entries: StateFlow<List<ActionLogEntry>> = _entries

    @Synchronized
    fun init(context: Context) {
        if (dao != null) return
        val d = MobileClawDb.get(context).actionLog()
        dao = d

        val buffered = preInit.toList()
        preInit.clear()

        scope.launch {
            buffered.forEach { d.insert(it.toEntity()) }
            purge(d)
            // Room re-runs this query on every write to action_log, so the UI stays
            // live without the store tracking its own copy of the list.
            d.observeRecent(OBSERVED_ENTRIES).collect { rows ->
                _entries.value = rows.asReversed().map { it.toEntry() }
            }
        }
    }

    /**
     * Records one action invocation. [suppliedText], if any, is redacted here so
     * callers can pass the raw text without worrying about leaks.
     */
    @Synchronized
    fun record(
        appName: String,
        key: String,
        actionTitle: String,
        suppliedText: String?,
        result: String,
        success: Boolean
    ) {
        val entry = ActionLogEntry(
            timeMs = System.currentTimeMillis(),
            appName = appName,
            key = key,
            actionTitle = actionTitle,
            suppliedText = suppliedText?.let { ReasoningModule.redact(it) },
            result = result,
            success = success
        )
        Log.i(TAG, entry.toLogLine())

        val d = dao
        if (d == null) {
            // Buffer rather than drop: an audit entry lost because the process wasn't
            // fully up is still an audit entry lost.
            preInit += entry
            _entries.value = (_entries.value + entry).takeLast(OBSERVED_ENTRIES)
            return
        }

        val shouldPurge = ++insertsSincePurge >= PURGE_INTERVAL
        if (shouldPurge) insertsSincePurge = 0
        scope.launch {
            d.insert(entry.toEntity())
            if (shouldPurge) purge(d)
        }
    }

    /**
     * How many rows are actually on disk, which is up to [MAX_ROWS] and so can be far
     * more than [entries] carries. Read on demand rather than kept in a flow: it exists
     * so a UI can be honest about showing a window onto a larger record, and nothing
     * needs it often enough to justify a second query re-running on every write.
     *
     * Falls back to the observed count before [init], when there is no database to ask.
     */
    suspend fun total(): Int = dao?.count() ?: _entries.value.size

    /** Wipes the audit trail — user-initiated only (see the "clear history" action). */
    @Synchronized
    fun clear() {
        preInit.clear()
        val d = dao
        if (d == null) {
            _entries.value = emptyList()
            return
        }
        // The DAO flow re-emits an empty list once the delete commits.
        scope.launch { d.deleteAll() }
    }

    private suspend fun purge(d: ActionLogDao) {
        try {
            val byAge = d.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
            val byCount = d.trimToNewest(MAX_ROWS)
            if (byAge > 0 || byCount > 0) {
                Log.i(TAG, "Retention sweep: dropped $byAge aged + $byCount overflow rows")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Retention sweep failed", e)
        }
    }

    private fun ActionLogEntry.toEntity() = ActionLogEntity(
        timeMs = timeMs,
        appName = appName,
        key = key,
        actionTitle = actionTitle,
        suppliedText = suppliedText,
        result = result,
        success = success
    )

    private fun ActionLogEntity.toEntry() = ActionLogEntry(
        timeMs = timeMs,
        appName = appName,
        key = key,
        actionTitle = actionTitle,
        suppliedText = suppliedText,
        result = result,
        success = success
    )
}
