package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * What the agent knows about the user, as opposed to what it did.
 *
 * **This is state, not a log**, and that one distinction drives everything unusual about
 * it next to the app's other three persisted stores. Those are append-only, most valuable
 * when recent, and worthless once old, so they sweep by age. This one is small, curated
 * and valuable indefinitely — an age sweep here would delete the feature, on a schedule,
 * silently. What bounds it is curation (the daily pass), not `DELETE WHERE timeMs <`.
 * The row cap in [MAX_ROWS] is a storm backstop that a healthy store never approaches, and
 * even that refuses to touch a [MemoryProvenance.USER_STATED] row.
 *
 * It takes the [ActionLogStore] shape — writes are rare and deliberate, so the flow is fed
 * by a DAO [kotlinx.coroutines.flow.Flow] Room re-emits and the insert goes to an internal
 * IO scope — with **one deviation**: [remember] is `suspend`. It has to be, because it
 * reads the turn's evidence out of `coroutineContext` (see [AgentTurn]), and its only
 * caller is a `suspend` tool anyway. Nothing about that makes the caller wait on disk; the
 * insert is still launched.
 *
 * **What may be written is not this class's decision** — [MemoryPolicy] makes it, and
 * `MemoryStore` is where that call happens rather than inside the skill, per the memory-write policy. Two
 * things follow, and neither is optional:
 *
 *  - Content is redacted unless every secret in it is one the user typed this turn. So this is the one table in the app that can hold unredacted sensitive text,
 *    which is why [DataExport]'s warning names it.
 *  - A write needs an [AgentTurn] in scope and spends one of that turn's allowance
 *    ([AgentTurn.MAX_MEMORY_WRITES_PER_TURN]). No turn means no write at all — an
 *    injection that reached a tool loop with no user message behind it is exactly the
 *    caller that should not be able to teach the assistant anything.
 *
 * There is deliberately **no deduplication** here. Folding duplicates together is the daily pass's
 * job, it needs the LLM to tell "Priya is my advisor" from "Priya advises me", and a cheap
 * exact-match check in the write path would only catch the case that never happens.
 */
object MemoryStore {

    private const val TAG = "MobileClaw-MEM"

    /** How many live rows the UI-facing [memories] flow carries. */
    const val OBSERVED_ENTRIES = 200

    /**
     * Storm backstop, not a retention policy. Sized so it never binds in normal
     * use — a few memories a day reaches it in years — because the number that actually
     * governs this store is [MemoryInjection]'s budget, which is measured in tokens and is
     * what the user should be told about.
     */
    private const val MAX_ROWS = 2_000

    /**
     * How long a superseded row stays recoverable after the daily pass folds it away.
     * These are the one thing here that *is* swept by age, because they are an undo window
     * rather than memories: a consolidation that turns out to be wrong has to be
     * recoverable for long enough that a user could plausibly notice it.
     */
    private val SUPERSEDED_RETENTION_MS = TimeUnit.DAYS.toMillis(30)

    /** Rows inserted between sweeps. Far rarer than [ActionLogStore]'s, so far smaller. */
    private const val PURGE_INTERVAL = 20

    /**
     * One thing the agent knows. [redacted] says whether [content] differs from what the
     * model wrote, which the review screen needs to be honest about what was kept.
     */
    data class Memory(
        val id: Long,
        val content: String,
        val provenance: MemoryProvenance,
        val redacted: Boolean,
        val createdMs: Long,
        val dayKey: String?,
        val pinned: Boolean
    ) {
        /**
         * How this row is presented to the model.
         *
         * **Data, never instructions**. The provenance is rendered on every line —
         * storing it and then not showing it would be pure decoration, and a
         * `MODEL_INFERRED` memory derived from a notification must not read like something
         * the user said. The `[…]` prefix and the id are also what make [ForgetMemoryTool]
         * usable: the model can only forget what it can name.
         *
         * **One memory is exactly one line**, which is why the content is flattened here
         * rather than at either call site. Both callers present a list, and a stored
         * newline is otherwise a way for one memory to forge a second entry — its own
         * `[#… · stated by the user]` prefix included, in a list where the provenance tag
         * is the only thing separating what the user said from what a notification did.
         */
        fun renderForModel(): String {
            val tag = when (provenance) {
                MemoryProvenance.USER_STATED -> "stated by the user"
                MemoryProvenance.MODEL_INFERRED -> "inferred, unverified"
                MemoryProvenance.DAILY_SUMMARY -> "daily summary${dayKey?.let { " for $it" } ?: ""}"
            }
            return "[#$id · $tag] ${content.replace(Regex("\\s*[\\r\\n]+\\s*"), " ")}"
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var dao: MemoryDao? = null

    /**
     * Held only for [applyDailyPass]'s transaction. Everything else in this class goes
     * through [dao], because a store that reaches for the database has usually started
     * doing something a DAO method should be doing instead.
     */
    @Volatile
    private var db: MobileClawDb? = null

    private var insertsSincePurge = 0

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())

    /** Live memories, newest first. Empty until [init] has run. */
    val memories: StateFlow<List<Memory>> = _memories

    /**
     * Unlike [ActionLogStore], there is no pre-init buffer. An audit entry lost because the
     * process wasn't up is evidence lost; a memory the model tried to write in that window
     * is one the user can simply ask for again, and buffering would mean holding
     * unclassified content — content whose evidence has already gone — until a DAO appears.
     */
    @Synchronized
    fun init(context: Context) {
        if (dao != null) return
        val database = MobileClawDb.get(context)
        val d = database.memories()
        db = database
        dao = d
        scope.launch {
            purge(d)
            d.observeLive(OBSERVED_ENTRIES).collect { rows ->
                _memories.value = rows.map { it.toMemory() }
            }
        }
    }

    /** What happened to a [remember] call, in terms the tool can explain to the model. */
    sealed interface WriteOutcome {
        data class Stored(val provenance: MemoryProvenance, val redacted: Boolean) : WriteOutcome
        /** This turn has spent its allowance. */
        data object TurnCapReached : WriteOutcome
        /** No [AgentTurn] in scope, so nothing attests anything. Fails closed. */
        data object NoTurnInScope : WriteOutcome
        data object Empty : WriteOutcome
        data object NotReady : WriteOutcome
    }

    /**
     * Stores something the agent should know, subject to [MemoryPolicy].
     *
     * Takes no provenance parameter, from any caller: the label is decided by what the
     * user actually typed this turn, and there is nothing here for a tool call to assert.
     */
    suspend fun remember(rawContent: String): WriteOutcome {
        val content = rawContent.trim()
        if (content.isEmpty()) return WriteOutcome.Empty

        val turn = coroutineContext[AgentTurn] ?: return WriteOutcome.NoTurnInScope
        if (!turn.tryClaimMemoryWrite()) {
            Log.w(TAG, "turn cap reached after ${turn.attemptedWrites} attempted writes")
            return WriteOutcome.TurnCapReached
        }

        val d = dao ?: return WriteOutcome.NotReady
        val write = MemoryPolicy.classify(content, turn.evidence)
        val now = System.currentTimeMillis()

        val shouldPurge = synchronized(this) {
            (++insertsSincePurge >= PURGE_INTERVAL).also { if (it) insertsSincePurge = 0 }
        }
        scope.launch {
            d.insert(
                MemoryEntity(
                    content = write.content,
                    provenance = write.provenance.name,
                    redacted = write.redacted,
                    createdMs = now,
                    updatedMs = now,
                    dayKey = null,
                    supersededBy = null,
                    pinned = false
                )
            )
            if (shouldPurge) purge(d)
        }
        // The content is not logged, at any level. It is the one thing in the app that may
        // be unredacted by design, and logcat is readable by anyone with adb.
        Log.i(TAG, "remembered ${write.provenance} (redacted=${write.redacted}, ${content.length} chars)")
        return WriteOutcome.Stored(write.provenance, write.redacted)
    }

    /**
     * Live memories matching [query], newest first — a plain case-insensitive term match.
     *
     * Not embeddings, and not by accident: there is no bundled local model, and a cloud
     * embedding call per recall re-introduces exactly the per-turn cost memory injection is trying to
     * bound. The injection rule is recency + provenance + pinning for the same reason. Revisit
     * with `usage` numbers showing this is the thing that hurts, not before.
     */
    suspend fun recall(query: String?, limit: Int = RECALL_LIMIT): List<Memory> {
        val d = dao ?: return emptyList()
        val rows = d.liveRows(MAX_ROWS).map { it.toMemory() }
        val terms = query?.lowercase()?.split(' ')?.filter { it.length > 2 }.orEmpty()
        if (terms.isEmpty()) return rows.take(limit)
        return rows.filter { m -> terms.any { m.content.lowercase().contains(it) } }.take(limit)
    }

    /** Deletes one memory outright. The user's own [MemoryActivity] uses this too. */
    suspend fun forget(id: Long): Boolean = (dao?.deleteById(id) ?: 0) > 0

    /**
     * Pins or unpins a memory, which is what puts it in every system prompt.
     *
     * **User-initiated only**, from [MemoryActivity] — there is no `pin` tool and there
     * must not be one. Pinning is the one way a [MemoryProvenance.MODEL_INFERRED] row
     * reaches the always-injected half, so a model that could pin its own inferences
     * would be able to promote untrusted content into the tier the memory trust policy keeps it out of —
     * laundering provenance by a different route than the one the daily pass is forbidden to take.
     */
    suspend fun setPinned(id: Long, pinned: Boolean): Boolean =
        (dao?.setPinned(id, pinned) ?: 0) > 0

    /** What one daily pass changed, for the log line nobody is awake to read live. */
    data class DailyPassResult(
        val summaryWritten: Boolean,
        val merged: Int,
        val retired: Int,
        val refusedBySql: Int
    )

    /**
     * Applies a vetted [DailyPass.Plan] — **all of it or none of it**.
     *
     * One transaction, because a half-applied consolidation is the failure the user cannot
     * see and cannot undo: rows superseded by a merge whose replacement was never inserted
     * are simply gone from every read path, and nothing later can tell that apart from the
     * model having decided to forget them. Either the new rows exist and the old ones point
     * at them, or nothing happened and the day is skipped.
     *
     * Two rules are enforced here rather than in [DailyPass.vet], because they are about
     * what the *store* can represent:
     *
     *  - **A retired row is superseded by that day's summary**, so with no summary there is
     *    nothing to point it at and [DailyPass.Plan.stale] is not applied. `supersededBy` is
     *    a link to the row that replaced this one; a retirement pointing at nothing would
     *    make it a delete with extra steps and take the undo window with it.
     *  - **Nothing written here is `USER_STATED`.** There is no [AgentTurn] behind this
     *    call, so [MemoryPolicy] would classify its content as inferred anyway; writing the
     *    provenance directly rather than going through `remember` keeps that explicit and
     *    keeps the pass out of the turn-based write allowance, which is about a
     *    conversation and not about a scheduled job.
     */
    suspend fun applyDailyPass(plan: DailyPass.Plan): DailyPassResult? {
        val d = dao ?: return null
        val database = db ?: return null
        if (plan.isEmpty) return DailyPassResult(false, 0, 0, 0)

        return try {
            database.withTransaction {
                val now = System.currentTimeMillis()

                val summaryId = plan.summary?.let {
                    d.insert(
                        MemoryEntity(
                            content = it,
                            provenance = MemoryProvenance.DAILY_SUMMARY.name,
                            redacted = plan.summaryRedacted,
                            createdMs = now,
                            updatedMs = now,
                            dayKey = plan.dayKey,
                            supersededBy = null,
                            pinned = false
                        )
                    )
                }

                var merged = 0
                var asked = 0
                plan.merges.forEach { m ->
                    val newId = d.insert(
                        MemoryEntity(
                            content = m.content,
                            provenance = MemoryProvenance.MODEL_INFERRED.name,
                            redacted = m.redacted,
                            createdMs = now,
                            updatedMs = now,
                            dayKey = null,
                            supersededBy = null,
                            pinned = false
                        )
                    )
                    merged += d.supersede(m.replaces, newId, now)
                    asked += m.replaces.size
                }

                val retired = summaryId?.let { d.supersede(plan.stale, it, now) } ?: 0
                asked += if (summaryId != null) plan.stale.size else 0

                DailyPassResult(
                    summaryWritten = summaryId != null,
                    merged = merged,
                    retired = retired,
                    // Rows the DAO's own clauses refused after vetting had passed them.
                    // Should always be zero; if it is not, the two checks disagree and the
                    // stricter one won, which is worth seeing in a log.
                    refusedBySql = asked - merged - retired
                ).also {
                    Log.i(
                        TAG,
                        "daily pass ${plan.dayKey}: summary=${it.summaryWritten} " +
                            "merged=${it.merged} retired=${it.retired} " +
                            "refusedBySql=${it.refusedBySql} rejectedByVet=${plan.rejected}"
                    )
                }
            }
        } catch (e: Exception) {
            // Nothing was applied — the transaction saw to that. The day is simply not
            // consolidated, which is the same state as a pass that never ran.
            Log.w(TAG, "daily pass ${plan.dayKey} rolled back", e)
            null
        }
    }

    /** Rows on disk including superseded ones, so a UI can be honest about its window. */
    suspend fun total(): Int = dao?.count() ?: _memories.value.size

    /**
     * Wipes every memory — user-initiated only, from Settings, with its own confirm.
     *
     * Deliberately **not** part of "Clear agent history": that action means "delete
     * the record of what you did", and this is the opposite — the only data in the app the
     * user explicitly asked to keep. Folding the two together would delete it without
     * asking.
     */
    @Synchronized
    fun clear() {
        val d = dao
        if (d == null) {
            _memories.value = emptyList()
            return
        }
        scope.launch { d.deleteAll() }
    }

    private suspend fun purge(d: MemoryDao) {
        try {
            val superseded = d.deleteSupersededOlderThan(
                System.currentTimeMillis() - SUPERSEDED_RETENTION_MS
            )
            val overflow = d.trimNonUserStated(MAX_ROWS)
            if (superseded > 0 || overflow > 0) {
                Log.i(TAG, "sweep: dropped $superseded superseded + $overflow overflow rows")
            }
            // Said out loud rather than silently made room for: the cap is meant never to
            // bind, and a store that is all user-stated rows has no overflow this sweep is
            // allowed to touch. If this ever fires, the answer is the daily pass, not a bigger cap.
            val live = d.countLive()
            if (live > MAX_ROWS) {
                Log.w(TAG, "$live live memories exceeds the $MAX_ROWS cap and none are evictable")
            }
        } catch (e: Exception) {
            Log.w(TAG, "memory sweep failed", e)
        }
    }

    private const val RECALL_LIMIT = 20

    private fun MemoryEntity.toMemory() = Memory(
        id = id,
        content = content,
        provenance = MemoryProvenance.fromString(provenance),
        redacted = redacted,
        createdMs = createdMs,
        dayKey = dayKey,
        pinned = pinned
    )
}
