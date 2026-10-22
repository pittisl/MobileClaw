package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Durable log of background triage decisions. Persistence preserves the explanation
 * for unattended actions and gives [ReasoningModule] recent decisions after a restart.
 *
 * Like [ActionLogStore], writes are infrequent and fire-and-forget: [add] launches an
 * insert on an IO scope, and [decisions] follows the DAO Flow. Streaming chat instead
 * uses memory-only interim updates, which this store does not need.
 *
 * [MobileClawApp] calls [init] at process start. Writes arriving before initialization
 * are buffered and flushed rather than dropped.
 */
object DecisionStore {

    private const val TAG = "MobileClaw-DS"

    /**
     * How many rows the in-memory [decisions] flow carries. Unchanged from the old
     * in-memory cap. Far more than anything reads — [ReasoningModule] takes the last
     * five — but it is one list of small objects, and a window that only just covers
     * its one consumer would break the moment a second one wanted more.
     */
    private const val OBSERVED_ENTRIES = 200

    /**
     * Retention, and the reason these two numbers are not [ActionLogStore]'s.
     *
     * This store's write rate is **device-paced, not user-paced**: one row per
     * notification, so a normally-used phone produces a few hundred a day, against a
     * handful of rows a day for the audit trail and a few dozen for chat. Copying
     * `5 000 rows / 90 days` would have made the 90 days decorative — the row cap
     * would bind after about two weeks and the age sweep would never once fire, so
     * "we keep 90 days of decisions" would have been false on every real device.
     *
     * So the numbers are picked the other way round: **30 days is the rule**, and at a
     * few hundred rows a day that lands comfortably under [MAX_ROWS], which is left as
     * the backstop for a notification storm rather than the thing normally in force.
     * A row is small (short strings, no blobs — `CapturedNotif.visualAssets` bitmaps
     * are deliberately not persisted), so 20 000 of them is single-digit megabytes.
     *
     * The value of an old decision is forensic, not functional: nothing reads past the
     * newest five. That is why this is the store where a shorter honest window beats a
     * longer one that never applies.
     */
    private const val MAX_ROWS = 20_000

    private val RETENTION_MS = TimeUnit.DAYS.toMillis(30)

    /** Rows inserted between retention sweeps — sweeping on every insert is wasteful. */
    private const val PURGE_INTERVAL = 50

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var dao: ActionDecisionDao? = null

    /** Decisions made before [init]; flushed to the DB in order once the DAO exists. */
    private val preInit = mutableListOf<Pair<Long, ActionDecision>>()

    private var insertsSincePurge = 0

    private val _decisions = MutableStateFlow<List<ActionDecision>>(emptyList())

    /** The newest [OBSERVED_ENTRIES] decisions, oldest first. */
    val decisions: StateFlow<List<ActionDecision>> = _decisions

    @Synchronized
    fun init(context: Context) {
        if (dao != null) return
        val d = MobileClawDb.get(context).actionDecisions()
        dao = d

        val buffered = preInit.toList()
        preInit.clear()

        scope.launch {
            buffered.forEach { (timeMs, decision) -> d.insert(decision.toEntity(timeMs)) }
            purge(d)
            // Room re-runs this query on every write, so the flow stays live without the
            // store keeping its own copy of the list.
            d.observeRecent(OBSERVED_ENTRIES).collect { rows ->
                _decisions.value = rows.asReversed().map { it.toDecision() }
            }
        }
    }

    /**
     * Records one triage decision. [ActionDecision.reasoning] and
     * [ActionDecision.replyText] are redacted here, so callers pass the model's output
     * through unchanged.
     *
     * Redacting is not belt-and-braces even though the model's *input* is already
     * redacted ([ReasoningModule] scrubs title, body, ticker and sub-text before they
     * reach the prompt). Two paths get round that: both fields are free text the model
     * generated rather than anything we vetted, and [ActionDecision.fallback] puts a raw
     * **exception message** in `reasoning`, which never went through the prompt at all.
     * This store is a redact-before-write boundary like
     * [ActionLogStore], because what lands here leaves the device through [DataExport].
     */
    @Synchronized
    fun add(d: ActionDecision) {
        val decision = d.copy(
            reasoning = ReasoningModule.redact(d.reasoning),
            replyText = ReasoningModule.redact(d.replyText)
        )
        val timeMs = System.currentTimeMillis()

        val dao = dao
        if (dao == null) {
            preInit += timeMs to decision
            _decisions.value = (_decisions.value + decision).takeLast(OBSERVED_ENTRIES)
            return
        }

        val shouldPurge = ++insertsSincePurge >= PURGE_INTERVAL
        if (shouldPurge) insertsSincePurge = 0
        scope.launch {
            dao.insert(decision.toEntity(timeMs))
            if (shouldPurge) purge(dao)
        }
    }

    /**
     * How many decisions are actually on disk, which is up to [MAX_ROWS] and so can be
     * far more than [decisions] carries. Falls back to the observed count before [init],
     * when there is no database to ask.
     */
    suspend fun total(): Int = dao?.count() ?: _decisions.value.size

    /** Wipes the decision log — user-initiated only (see the "clear history" action). */
    @Synchronized
    fun clear() {
        preInit.clear()
        val d = dao
        if (d == null) {
            _decisions.value = emptyList()
            return
        }
        // The DAO flow re-emits an empty list once the delete commits.
        scope.launch { d.deleteAll() }
    }

    private suspend fun purge(d: ActionDecisionDao) {
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

    private fun ActionDecision.toEntity(timeMs: Long) = ActionDecisionEntity(
        timeMs = timeMs,
        notificationKey = notificationKey,
        action = action.name,
        priority = priority.name,
        reasoning = reasoning,
        replyText = replyText,
        confidence = confidence,
        snoozeDurationMinutes = snoozeDurationMinutes,
        actionTitle = actionTitle
    )

    private fun ActionDecisionEntity.toDecision() = ActionDecision(
        notificationKey = notificationKey,
        // fromString, not valueOf: a row written by a build that had a constant this one
        // doesn't must degrade to the safe default, not crash the listener on restore.
        action = ActionDecision.Action.fromString(action),
        priority = ActionDecision.Priority.fromString(priority),
        reasoning = reasoning,
        replyText = replyText,
        confidence = confidence,
        snoozeDurationMinutes = snoozeDurationMinutes,
        actionTitle = actionTitle
    )
}
