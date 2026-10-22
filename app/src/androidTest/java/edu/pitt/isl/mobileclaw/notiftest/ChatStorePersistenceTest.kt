package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins down *which* chat turns reach disk.
 *
 * The split matters for both cost and correctness: [ChatStore.replaceAt] runs ~20×
 * a second while a reply streams, so persisting there would hammer the database for
 * nothing, and a crash mid-stream would leave a half-written bubble (or worse, a
 * pending placeholder with no agent left to complete it) in restored history.
 */
@RunWith(AndroidJUnit4::class)
class ChatStorePersistenceTest {

    private lateinit var context: Context
    private lateinit var dao: ChatMessageDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ChatStore.init(context)
        dao = MobileClawDb.get(context).chatMessages()
        ChatStore.clear()
        awaitStoredCount(0)
    }

    @After
    fun tearDown() {
        ChatStore.clear()
    }

    private fun storedCount(): Int = runBlocking { dao.count(ChatStore.DEFAULT_SESSION) }

    private fun stored() = runBlocking { dao.recent(ChatStore.DEFAULT_SESSION, 50) }

    private fun awaitStoredCount(expected: Int) =
        awaitUntil(message = "expected $expected stored rows, saw ${storedCount()}") {
            storedCount() == expected
        }

    @Test
    fun add_persistsSettledTurns() {
        ChatStore.add(ChatMessage(role = ChatMessage.Role.USER, content = "what's urgent?"))
        awaitStoredCount(1)

        val row = stored().single()
        assertEquals("USER", row.role)
        assertEquals("what's urgent?", row.content)
        assertEquals(ChatStore.DEFAULT_SESSION, row.sessionId)
    }

    @Test
    fun add_skipsPendingPlaceholders() {
        ChatStore.add(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", pending = true))
        // Give a write the chance to land before asserting it never happens.
        Thread.sleep(300)
        assertEquals(0, storedCount())
        assertEquals(1, ChatStore.messages.value.size) // but the UI does show it
    }

    @Test
    fun add_skipsErrorBubbles() {
        ChatStore.add(
            ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "Network error", isError = true)
        )
        Thread.sleep(300)
        assertEquals(0, storedCount())
    }

    /** The streaming path: many replaceAt calls, then one finalize — exactly one row. */
    @Test
    fun replaceAtStreams_inMemoryOnly_andFinalizeCommitsOnce() {
        val index = ChatStore.add(
            ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", pending = true)
        )
        repeat(20) { i ->
            ChatStore.replaceAt(
                index,
                ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "partial $i")
            )
        }
        Thread.sleep(300)
        assertEquals("streaming must not touch the database", 0, storedCount())

        ChatStore.finalize(
            index,
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                content = "the whole answer",
                actions = listOf("Call back", "Text her")
            )
        )
        awaitStoredCount(1)

        val row = stored().single()
        assertEquals("the whole answer", row.content)
        assertEquals(listOf("Call back", "Text her"), ChatConverters.toActions(row.actions))
    }

    @Test
    fun finalize_doesNotStoreAFailedTurn() {
        val index = ChatStore.add(
            ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", pending = true)
        )
        ChatStore.finalize(
            index,
            ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "HTTP 429", isError = true)
        )
        Thread.sleep(300)
        assertEquals(0, storedCount())
    }

    @Test
    fun systemTurns_fromTheBackgroundLoop_arePersisted() {
        ChatStore.add(ChatMessage(role = ChatMessage.Role.SYSTEM, content = "✅ Reply sent."))
        awaitStoredCount(1)
        assertEquals("SYSTEM", stored().single().role)
    }

    @Test
    fun clear_emptiesStoredHistoryNotJustTheView() {
        ChatStore.add(ChatMessage(role = ChatMessage.Role.USER, content = "hello"))
        awaitStoredCount(1)

        ChatStore.clear()

        assertTrue(ChatStore.messages.value.isEmpty())
        awaitStoredCount(0)
    }

    /** Writes go through a single-consumer channel, so order is guaranteed, not hoped for. */
    @Test
    fun writesCommitInSubmissionOrder() {
        repeat(15) { i ->
            ChatStore.add(ChatMessage(role = ChatMessage.Role.USER, content = "turn $i"))
        }
        awaitStoredCount(15)

        val contents = stored().asReversed().map { it.content }
        assertEquals((0 until 15).map { "turn $it" }, contents)
    }
}
