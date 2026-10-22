package edu.pitt.isl.mobileclaw.notiftest

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One conversation, including metadata that cannot be derived from its messages.
 * An empty session still needs an id and creation time; last-used ordering can change
 * when the user switches sessions without sending a message.
 *
 * [agentSpecId] stores the selected agent id. [AgentRegistry.resolve] handles an
 * unavailable or legacy id when a turn runs, without rewriting the stored preference.
 *
 * A null [title] means unnamed: the first user turn can then supply an automatic title
 * without overwriting a title the user chose.
 */
@Entity(tableName = "chat_session")
data class ChatSessionEntity(
    @PrimaryKey val sessionId: String,
    val title: String?,
    val createdMs: Long,
    /** Bumped on every write, so the switcher lists what the user was last doing. */
    val lastActiveMs: Long,
    val agentSpecId: String
)

@Dao
interface ChatSessionDao {

    /** Most recently used first — the order the switcher shows. */
    @Query("SELECT * FROM chat_session ORDER BY lastActiveMs DESC, createdMs DESC")
    fun observeAll(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_session WHERE sessionId = :sessionId")
    suspend fun find(sessionId: String): ChatSessionEntity?

    @Upsert
    suspend fun upsert(session: ChatSessionEntity)

    @Query("UPDATE chat_session SET lastActiveMs = :nowMs WHERE sessionId = :sessionId")
    suspend fun touch(sessionId: String, nowMs: Long)

    /**
     * Names a session only if it has no name yet, so the automatic title taken from the
     * first user turn can never overwrite one the user typed. `IS NULL` in the statement
     * rather than a read-then-write, because the two writers are a UI action and a
     * background insert.
     */
    @Query("UPDATE chat_session SET title = :title WHERE sessionId = :sessionId AND title IS NULL")
    suspend fun nameIfUnnamed(sessionId: String, title: String)

    @Query("UPDATE chat_session SET title = :title WHERE sessionId = :sessionId")
    suspend fun rename(sessionId: String, title: String)

    @Query("DELETE FROM chat_session WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("SELECT COUNT(*) FROM chat_session")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(session: ChatSessionEntity)
}
