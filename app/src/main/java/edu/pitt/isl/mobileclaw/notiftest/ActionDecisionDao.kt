package edu.pitt.isl.mobileclaw.notiftest

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One triage decision on disk. Mirrors [ActionDecision], kept separate for the same
 * reason [ActionLogEntity] is: a schema change shouldn't ripple out to the prompt
 * builders and tools that pass the domain type around, and the domain type stays free
 * of Room annotations.
 *
 * Two differences from [ActionDecision] are deliberate:
 *
 *  - **[timeMs] exists only here.** [ActionDecision] carries no timestamp — nothing in
 *    the app ever needed one — but retention by age does, so the row is stamped when it
 *    is written. It is not mapped back on restore, because inventing a field on the
 *    domain type that only the database can fill would make it lie for every decision
 *    that hasn't been persisted yet.
 *  - **The enums are stored as their names, not ordinals.** `ActionDecision.Action` has
 *    seven values and `Priority` three; storing ordinals would silently re-point every
 *    stored row if anyone reorders or inserts a constant. `fromString` already exists on
 *    both companions and falls back safely, so a name that no longer exists degrades to
 *    `DO_NOTHING` / `LOW` instead of throwing on a row written by an older build.
 *
 * [reasoning] and [replyText] are already redacted when they reach here — redaction
 * happens in [DecisionStore.add], so nothing unredacted is written. The DB is
 * app-private, but it is also what [DataExport] hands to the user.
 */
@Entity(tableName = "action_decision", indices = [Index("timeMs")])
data class ActionDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val notificationKey: String,
    val action: String,
    val priority: String,
    val reasoning: String,
    val replyText: String,
    val confidence: Float,
    val snoozeDurationMinutes: Int?,
    val actionTitle: String
)

/**
 * Data access for the triage decision log.
 *
 * Same shape as [ActionLogDao] — the two stores have the same write profile (rare,
 * fire-and-forget, one row per event) and there is no reason for them to differ. Only
 * declarations live here; KSP generates the implementation and checks the SQL against
 * the schema at build time.
 */
@Dao
interface ActionDecisionDao {

    @Insert
    suspend fun insert(entry: ActionDecisionEntity)

    /** Newest first. [DecisionStore] reverses it to keep the previous oldest-first order. */
    @Query("SELECT * FROM action_decision ORDER BY timeMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActionDecisionEntity>>

    /** One calendar day's decisions for the daily pass — half-open, like [ActionLogDao.rowsBetween]. */
    @Query(
        "SELECT * FROM action_decision WHERE timeMs >= :fromMs AND timeMs < :toMs " +
            "ORDER BY timeMs DESC, id DESC LIMIT :limit"
    )
    suspend fun rowsBetween(fromMs: Long, toMs: Long, limit: Int): List<ActionDecisionEntity>

    @Query("SELECT COUNT(*) FROM action_decision WHERE timeMs >= :fromMs AND timeMs < :toMs")
    suspend fun countBetween(fromMs: Long, toMs: Long): Int

    @Query("DELETE FROM action_decision WHERE timeMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    /**
     * Drops everything outside the newest [keep] rows. Ordered by `id` rather than
     * `timeMs` so a device clock change can't strand rows as permanently unreachable
     * by the retention sweep.
     */
    @Query("DELETE FROM action_decision WHERE id NOT IN (SELECT id FROM action_decision ORDER BY id DESC LIMIT :keep)")
    suspend fun trimToNewest(keep: Int): Int

    @Query("DELETE FROM action_decision")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM action_decision")
    suspend fun count(): Int
}
