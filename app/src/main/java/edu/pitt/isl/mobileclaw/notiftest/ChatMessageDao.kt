package edu.pitt.isl.mobileclaw.notiftest

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * One persisted chat turn, associated with its conversation through [sessionId].
 * Only settled turns reach this table. [ChatStore.finalize] excludes pending
 * placeholders and error bubbles, so a crash cannot restore an unfinished response.
 */
@Entity(
    tableName = "chat_message",
    indices = [Index(value = ["sessionId", "timestampMs"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestampMs: Long,
    /** Quick-reply labels, JSON-encoded via [ChatConverters]. */
    val actions: String
)

/**
 * Stores `List<String>` as a JSON array rather than a delimiter-joined string.
 * Quick-reply labels come from splitting the model's `@@ACTIONS@@` line on "|", so
 * a label can contain almost anything; a delimiter would eventually meet its own
 * delimiter and silently split one label into two.
 */
object ChatConverters {

    @TypeConverter
    fun fromActions(actions: List<String>): String =
        JSONArray().apply { actions.forEach { put(it) } }.toString()

    @TypeConverter
    fun toActions(json: String): List<String> = try {
        JSONArray(json).let { arr -> (0 until arr.length()).map { arr.getString(it) } }
    } catch (_: Exception) {
        emptyList()
    }
}

@Dao
interface ChatMessageDao {

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    /** Newest first; [ChatStore] reverses to restore chronological order. */
    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    suspend fun recent(sessionId: String, limit: Int): List<ChatMessageEntity>

    @Query("DELETE FROM chat_message WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_message WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    /** Ordered by `id` so a device clock change can't strand rows out of the sweep's reach. */
    @Query("DELETE FROM chat_message WHERE id NOT IN (SELECT id FROM chat_message ORDER BY id DESC LIMIT :keep)")
    suspend fun trimToNewest(keep: Int): Int

    @Query("SELECT COUNT(*) FROM chat_message WHERE sessionId = :sessionId")
    suspend fun count(sessionId: String): Int
}
