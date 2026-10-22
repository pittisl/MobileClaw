package edu.pitt.isl.mobileclaw.notiftest

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One row of the action audit trail. Mirrors [ActionLogStore.ActionLogEntry] — the
 * entity is kept separate from the domain type so a schema change (new column,
 * renamed field) doesn't ripple out to every caller of
 * [ActionLogStore.record], and so the domain type stays free of Room annotations.
 *
 * [suppliedText] is already redacted when it reaches here; redaction happens in
 * [ActionLogStore.record] so nothing unredacted is ever written to disk (the DB is
 * app-private, but it is also what the user can export — see [DataExport]).
 */
@Entity(tableName = "action_log", indices = [Index("timeMs")])
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val appName: String,
    val key: String,
    val actionTitle: String,
    val suppliedText: String?,
    val result: String,
    val success: Boolean
)

/**
 * Data access for the action audit trail.
 *
 * Only declarations live here — KSP generates the cursor-mapping implementation at
 * build time, and the SQL in each [Query] is checked against the schema during
 * compilation, so a wrong column name is a build failure rather than a crash on the
 * device.
 *
 * [observeRecent] returns a [Flow] that Room re-emits on every write to the table,
 * which is what lets [ActionLogStore] keep exposing a `StateFlow` to the UI with no
 * change to its public shape.
 */
@Dao
interface ActionLogDao {

    @Insert
    suspend fun insert(entry: ActionLogEntity)

    /** Newest first. [ActionLogStore] reverses it to keep the previous oldest-first order. */
    @Query("SELECT * FROM action_log ORDER BY timeMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActionLogEntity>>

    /**
     * One calendar day's rows, newest first, for the daily pass's summary.
     *
     * Half-open on purpose — `>= from AND < to` — so a row at midnight belongs to one day
     * and not to both, matching [DailyPass.boundsOf]. [limit] is the daily pass's cap:
     * the pass must stay one bounded call however busy the phone was.
     */
    @Query(
        "SELECT * FROM action_log WHERE timeMs >= :fromMs AND timeMs < :toMs " +
            "ORDER BY timeMs DESC, id DESC LIMIT :limit"
    )
    suspend fun rowsBetween(fromMs: Long, toMs: Long, limit: Int): List<ActionLogEntity>

    /** How many rows the day actually held, so a truncated section can say so honestly. */
    @Query("SELECT COUNT(*) FROM action_log WHERE timeMs >= :fromMs AND timeMs < :toMs")
    suspend fun countBetween(fromMs: Long, toMs: Long): Int

    @Query("DELETE FROM action_log WHERE timeMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    /**
     * Drops everything outside the newest [keep] rows. Ordered by `id` rather than
     * `timeMs` so a device clock change can't strand rows as permanently unreachable
     * by the retention sweep.
     */
    @Query("DELETE FROM action_log WHERE id NOT IN (SELECT id FROM action_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trimToNewest(keep: Int): Int

    @Query("DELETE FROM action_log")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM action_log")
    suspend fun count(): Int
}
