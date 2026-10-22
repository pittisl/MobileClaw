package edu.pitt.isl.mobileclaw.notiftest

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One persisted memory, mirroring [MemoryStore.Memory].
 *
 * - [provenance] is a stored name, not an ordinal. [MemoryPolicy] assigns it; unknown
 *   values resolve to MODEL_INFERRED rather than a trusted tier.
 * - [redacted] records whether stored [content] differs from the submitted text, so
 *   the review screen can explain whether sensitive content was kept or scrubbed.
 * - [supersededBy] links to a replacement rather than deleting the old row. Only
 *   superseded rows expire by age, allowing review of a mistaken consolidation.
 * - [pinned] is the user's selection input for memory injection. It is the only way
 *   an inferred memory can enter the always-injected set.
 *
 * [content] is redacted unless every sensitive span is attested by the user's words
 * in this turn. This table can therefore hold unredacted sensitive text, which is
 * why [DataExport] warns before exporting it.
 */
@Entity(tableName = "memory", indices = [Index("createdMs")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val provenance: String,
    val redacted: Boolean,
    val createdMs: Long,
    /** Bumped when a row is superseded; that is what the age sweep on those rows reads. */
    val updatedMs: Long,
    /** `yyyy-MM-dd` for a [MemoryProvenance.DAILY_SUMMARY] row; null otherwise. */
    val dayKey: String?,
    val supersededBy: Long?,
    val pinned: Boolean
)

/**
 * Data access for the memory store.
 *
 * The queries differ from [ActionLogDao]'s because memory is persistent state rather
 * than an activity log:
 *
 *  - **There is no `deleteOlderThan` over live rows.** A 30-day sweep on a store whose
 *    entire purpose is "remember this" deletes the feature, on a schedule, silently.
 *    [deleteSupersededOlderThan] applies only to rows the daily pass has already folded
 *    away, which are an undo window and not memories.
 *  - **[trimNonUserStated] refuses to touch `USER_STATED` rows.** The row cap is a storm
 *    backstop, not a retention policy, and the one thing it must never do is quietly
 *    delete what the user explicitly asked to keep. If a store somehow fills with nothing
 *    but user-stated memories, the cap does not bind and [MemoryStore] says so in the log
 *    rather than making room.
 */
@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(entry: MemoryEntity): Long

    /**
     * Live rows — everything the daily pass hasn't folded away — newest first.
     * Room re-emits on every write, which is what feeds [MemoryStore.memories].
     */
    @Query("SELECT * FROM memory WHERE supersededBy IS NULL ORDER BY createdMs DESC, id DESC LIMIT :limit")
    fun observeLive(limit: Int): Flow<List<MemoryEntity>>

    /** One-shot read of live rows, for recall and for the injection budget. */
    @Query("SELECT * FROM memory WHERE supersededBy IS NULL ORDER BY createdMs DESC, id DESC LIMIT :limit")
    suspend fun liveRows(limit: Int): List<MemoryEntity>

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /**
     * Pins or unpins one row — the injection policy's user-controlled selection input, and the only one the user
     * drives directly.
     *
     * Deliberately does **not** touch [MemoryEntity.updatedMs]. That column means "when
     * this row stopped being current", which is what [deleteSupersededOlderThan] reads;
     * bumping it here would extend the undo window of a superseded row every time someone
     * pinned something, which is not what either field is for.
     */
    @Query("UPDATE memory SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean): Int

    /**
     * Drops rows the daily pass has superseded once the undo window has passed. Reads
     * [MemoryEntity.updatedMs] — the moment the row was folded away — rather than
     * `createdMs`, so the window is "30 days since it stopped being current", not
     * "30 days since it was first learned".
     */
    @Query("DELETE FROM memory WHERE supersededBy IS NOT NULL AND updatedMs < :cutoffMs")
    suspend fun deleteSupersededOlderThan(cutoffMs: Long): Int

    /**
     * The storm backstop. Drops rows outside the newest [keep] **that the user did not
     * state**, ordered by `id` rather than `createdMs` so a device clock change can't
     * strand rows as permanently unreachable — the same reasoning as [ActionLogDao].
     */
    @Query(
        "DELETE FROM memory WHERE provenance != 'USER_STATED' AND id NOT IN " +
            "(SELECT id FROM memory ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun trimNonUserStated(keep: Int): Int

    /**
     * Folds rows away by pointing them at the row that replaced them — a link rather
     * than a delete, so a consolidation that turns out to be wrong is recoverable for as
     * long as [deleteSupersededOlderThan]'s window.
     *
     * The `provenance != 'USER_STATED' AND pinned = 0` clauses duplicate
     * [DailyPass.isFoldable] on purpose, and for the reason [ShellCommandPolicy] is applied
     * a second time inside the privileged process: this is the check that still holds if
     * the vetting above it is bypassed, buggy, or forgotten by a caller added later. The
     * rule it re-states — an unattended pass may not retire what the user asked for or
     * pinned — is the one whose violation is silent at the time and unrecoverable after
     * thirty days.
     *
     * What SQL cannot re-check is [DailyPass.isFoldable]'s third clause, that the row holds
     * no secret; that one lives in Kotlin only. The count returned is therefore the number
     * of rows that *were* folded, not the number asked for, and the caller logs both.
     */
    @Query(
        "UPDATE memory SET supersededBy = :newId, updatedMs = :nowMs WHERE id IN (:ids) " +
            "AND supersededBy IS NULL AND provenance != 'USER_STATED' AND pinned = 0"
    )
    suspend fun supersede(ids: List<Long>, newId: Long, nowMs: Long): Int

    /**
     * Whether a day has already been summarised — the daily pass's idempotence check.
     *
     * A scheduled job is retried, re-enqueued and re-run on a schedule the app does not
     * fully control, so "did this already happen" has to be a question about the data
     * rather than about a flag someone remembered to set. Counts superseded summaries too:
     * a day whose summary was later folded into a weekly one has still been summarised, and
     * writing a second one would resurrect it.
     */
    @Query("SELECT COUNT(*) FROM memory WHERE dayKey = :dayKey AND provenance = 'DAILY_SUMMARY'")
    suspend fun countSummariesFor(dayKey: String): Int

    @Query("DELETE FROM memory")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM memory WHERE supersededBy IS NULL")
    suspend fun countLive(): Int

    @Query("SELECT COUNT(*) FROM memory")
    suspend fun count(): Int
}
