package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Multi-session chat against the real database and SharedPreferences.
 *
 * The tests verify that each session owns its rows, background writers resolve the
 * persisted active session, and a streaming turn finishes in the session where it
 * started even if the user switches away. A [ChatStore.Slot] must carry the session;
 * a bare list index could silently redirect a reply into the visible conversation.
 */
@RunWith(AndroidJUnit4::class)
class ChatSessionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ChatStore.init(context)
        resetToASingleEmptyDefault()
    }

    @After
    fun tearDown() = resetToASingleEmptyDefault()

    /** Leaves exactly the default session, empty and active — the state a fresh install has. */
    private fun resetToASingleEmptyDefault() {
        ChatStore.switchTo(ChatStore.DEFAULT_SESSION)
        ChatStore.sessions.value.filter { it.id != ChatStore.DEFAULT_SESSION }
            .forEach { ChatStore.deleteSession(it.id) }
        ChatStore.clear()
        awaitUntil(message = "sessions did not reset") {
            ChatStore.sessions.value.size <= 1 && ChatStore.messages.value.isEmpty()
        }
    }

    private fun storedIn(sessionId: String): List<ChatMessageEntity> = runBlocking {
        MobileClawDb.get(context).chatMessages().recent(sessionId, 100)
    }

    @Test
    fun turnsLandInTheirOwnSession_andSwitchingSwapsTheVisibleOne() {
        ChatStore.add(ChatMessage(role = ChatMessage.Role.USER, content = "about the default"))
        awaitUntil { storedIn(ChatStore.DEFAULT_SESSION).size == 1 }

        val second = ChatStore.newSession()
        assertEquals(second, ChatStore.activeSessionId.value)
        assertTrue("a new conversation starts empty", ChatStore.messages.value.isEmpty())

        ChatStore.add(ChatMessage(role = ChatMessage.Role.USER, content = "about the second"))
        awaitUntil { storedIn(second).size == 1 }
        assertEquals(1, ChatStore.messages.value.size)

        // Neither conversation has the other's turns, on disk or on screen.
        assertEquals(1, storedIn(ChatStore.DEFAULT_SESSION).size)
        assertEquals("about the second", storedIn(second).single().content)

        ChatStore.switchTo(ChatStore.DEFAULT_SESSION)
        awaitUntil(message = "the default session never came back") {
            ChatStore.messages.value.singleOrNull()?.content == "about the default"
        }
    }

    /**
     * The reason [ChatStore.Slot] exists. A turn is started in one conversation, the user
     * switches away mid-reply, and the answer must still finish where it was asked.
     */
    @Test
    fun aReplyFinishesInTheConversationItWasAskedIn() {
        ChatStore.add(ChatMessage(role = ChatMessage.Role.USER, content = "who is Priya?"))
        val slot = ChatStore.add(
            ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", pending = true)
        )
        assertEquals(ChatStore.DEFAULT_SESSION, slot.sessionId)

        val other = ChatStore.newSession()
        ChatStore.replaceAt(slot, ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "Pri…"))
        ChatStore.finalize(
            slot, ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "Priya is your advisor.")
        )

        // The conversation the user switched to is untouched — this is the assertion that
        // fails if a rewrite follows the screen instead of the slot.
        assertTrue("the answer must not appear here", ChatStore.messages.value.isEmpty())
        awaitUntil(message = "the answer never reached its own session") {
            storedIn(ChatStore.DEFAULT_SESSION).any { it.content == "Priya is your advisor." }
        }
        assertTrue(storedIn(other).isEmpty())

        ChatStore.switchTo(ChatStore.DEFAULT_SESSION)
        awaitUntil(message = "the finished answer is not in the conversation it belongs to") {
            ChatStore.messages.value.lastOrNull()?.content == "Priya is your advisor."
        }
    }

    /**
     * The active session reaches real `SharedPreferences`, because the readers include
     * `BroadcastReceiver`s whose process was started by the notification they are handling.
     * Same claim `AgentRunStateTest` makes about the pause flag, and the same limit: a test
     * inside the process cannot tell `commit()` from `apply()`.
     */
    @Test
    fun theActiveSessionIsPersisted_notJustHeldInMemory() {
        val second = ChatStore.newSession()

        val stored = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("chat_active_session", null)
        assertEquals(second, stored)
    }

    /** A background message has no session in hand; it belongs to the conversation on screen. */
    @Test
    fun backgroundWritersPostToTheActiveConversation() {
        val second = ChatStore.newSession()
        // The shape PendingAction, NotifConfirm and TestActionReceiver all use.
        ChatStore.add(ChatMessage(role = ChatMessage.Role.SYSTEM, content = "⚡ Auto: dismissed"))

        awaitUntil { storedIn(second).size == 1 }
        assertTrue(storedIn(ChatStore.DEFAULT_SESSION).isEmpty())
    }

    @Test
    fun deletingAConversationRemovesOnlyItsTurns() {
        ChatStore.add(ChatMessage(role = ChatMessage.Role.USER, content = "keep me"))
        val doomed = ChatStore.newSession()
        ChatStore.add(ChatMessage(role = ChatMessage.Role.USER, content = "delete me"))
        awaitUntil { storedIn(doomed).size == 1 }

        ChatStore.deleteSession(doomed)

        awaitUntil(message = "the doomed conversation's turns survived") {
            storedIn(doomed).isEmpty() && ChatStore.sessions.value.none { it.id == doomed }
        }
        assertEquals("keep me", storedIn(ChatStore.DEFAULT_SESSION).single().content)
        // Deleting what you were looking at has to leave you somewhere.
        assertEquals(ChatStore.DEFAULT_SESSION, ChatStore.activeSessionId.value)
    }

    /**
     * The last conversation is cleared rather than deleted. An app with no session has
     * nowhere to put the next background message, and none of those callers can ask for
     * one.
     */
    @Test
    fun theLastConversationIsClearedRatherThanDeleted() {
        ChatStore.add(ChatMessage(role = ChatMessage.Role.USER, content = "only turn"))
        awaitUntil { storedIn(ChatStore.DEFAULT_SESSION).size == 1 }

        ChatStore.deleteSession(ChatStore.DEFAULT_SESSION)

        awaitUntil(message = "the last conversation was not cleared") {
            storedIn(ChatStore.DEFAULT_SESSION).isEmpty()
        }
        // The turns going away proves nothing on its own — a delete removes them too. What
        // separates "cleared" from "deleted" is that the session row survives, so give a
        // rogue delete time to land before looking. (Without this wait and this assertion
        // the test passed with the guard removed, which is how it was found.)
        Thread.sleep(500)
        assertNotNull(
            "the last conversation must be emptied, not removed",
            runBlocking { MobileClawDb.get(context).chatSessions().find(ChatStore.DEFAULT_SESSION) }
        )
        assertTrue(ChatStore.sessions.value.isNotEmpty())
        assertEquals(ChatStore.DEFAULT_SESSION, ChatStore.activeSessionId.value)
    }

    /**
     * A conversation names itself from the first thing the user said, and a name the user
     * chose is never overwritten — the two are the same `UPDATE … WHERE title IS NULL`.
     */
    @Test
    fun theFirstUserTurnNamesTheConversation_andARenameSticks() {
        val session = ChatStore.newSession()
        ChatStore.add(
            ChatMessage(role = ChatMessage.Role.USER, content = "help me plan the trip to Boston")
        )
        awaitUntil(message = "the conversation never named itself") {
            ChatStore.sessions.value.firstOrNull { it.id == session }?.title != null
        }
        assertTrue(
            ChatStore.sessions.value.first { it.id == session }
                .displayTitle.startsWith("help me plan the trip")
        )

        ChatStore.rename(session, "Boston")
        awaitUntil { ChatStore.sessions.value.first { it.id == session }.title == "Boston" }

        // A later user turn must not rename it back.
        ChatStore.add(ChatMessage(role = ChatMessage.Role.USER, content = "what about the hotel?"))
        Thread.sleep(400)
        assertEquals("Boston", ChatStore.sessions.value.first { it.id == session }.title)
        assertFalse(ChatStore.sessions.value.first { it.id == session }.displayTitle == "Untitled chat")
    }

    // ---- per-session agents -------------------------------------------------------

    /**
     * A conversation's agent reaches disk and comes back on the [ChatStore.Session] the
     * switcher reads. The schema v5 column stores the per-session agent and is read back when resolving a turn.
     */
    @Test
    fun aSessionsAgentIsStoredAndReadBack() {
        val session = ChatStore.newSession()

        awaitUntil(message = "the session row never arrived") {
            ChatStore.sessions.value.any { it.id == session }
        }
        assertEquals(
            AgentRegistry.generalChat.id,
            ChatStore.sessions.value.first { it.id == session }.agentSpecId
        )
        assertEquals(
            AgentRegistry.generalChat.id,
            runBlocking { MobileClawDb.get(context).chatSessions().find(session)?.agentSpecId }
        )
    }

    /**
     * The case the synchronous map exists for, and the reason it is not just a read of
     * [ChatStore.sessions]: the row is still queued on the write channel when
     * [ChatStore.newSession] returns, so a user who types immediately would otherwise run
     * their first turn on whatever agent the fallback picks. Asserted **before** any wait,
     * deliberately — a `awaitUntil` here would test the DAO flow instead.
     */
    @Test
    fun aBrandNewSessionKnowsItsAgentBeforeTheRowLands() {
        val session = ChatStore.newSession()

        assertEquals(AgentRegistry.generalChat.id, ChatStore.agentSpecIdFor(session))
    }

    /**
     * A session whose stored agent no longer names one still opens, on the default agent.
     * Written straight through the DAO because that is the only way to produce the state —
     * an id that resolved when it was written and does not now, which is what a rename, a
     * removal or an older APK all leave behind.
     */
    @Test
    fun aSessionPointingAtAnAgentThisBuildLacksStillRuns() {
        val session = ChatStore.newSession()
        // Wait for the row `newSession` queued before overwriting it. Its upsert is on the
        // write channel, so a direct DAO write can otherwise land first and be undone by
        // the one still in the queue.
        awaitUntil(message = "the session row never arrived") {
            ChatStore.sessions.value.any { it.id == session }
        }

        val now = System.currentTimeMillis()
        runBlocking {
            MobileClawDb.get(context).chatSessions()
                .upsert(ChatSessionEntity(session, "orphaned", now, now, "agent-from-a-later-build"))
        }

        awaitUntil(message = "the rewritten row never arrived") {
            ChatStore.sessions.value.firstOrNull { it.id == session }?.agentSpecId ==
                "agent-from-a-later-build"
        }

        // Stored as written — the store records the choice rather than laundering it …
        assertEquals("agent-from-a-later-build", ChatStore.agentSpecIdFor(session))
        // … and the collapse to a usable agent happens at the point of use.
        assertEquals(
            AgentRegistry.generalChat.id,
            AgentRegistry.resolve(ChatStore.agentSpecIdFor(session)).id
        )
    }

    /**
     * Deleting a conversation forgets its agent too. Nothing user-visible depends on this;
     * it is here because the map is the one piece of session state that is not rebuilt from
     * the DAO flow, so it is the one that can outlive its row.
     */
    @Test
    fun deletingAConversationForgetsItsAgent() {
        val session = ChatStore.newSession()
        assertNotNull(ChatStore.agentSpecIdFor(session))

        ChatStore.switchTo(ChatStore.DEFAULT_SESSION)
        awaitUntil { ChatStore.sessions.value.size > 1 }
        ChatStore.deleteSession(session)

        assertNull(ChatStore.agentSpecIdFor(session))
    }
}
