package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.skills.search.FileIndex
import edu.pitt.isl.mobileclaw.notiftest.skills.search.IndexPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the production listener turn with scripted readers/model, no network or transport. */
@RunWith(AndroidJUnit4::class)
class NotificationPhotoFollowUpTest {
    private lateinit var context: Context
    private lateinit var originalSession: String
    private lateinit var session: String
    private var savedFollowUp: Boolean? = null
    private var oldPaused = true
    private val sessions = mutableListOf<String>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        savedFollowUp = if (prefs.contains(AppSettings.KEY_FOLLOW_UP)) prefs.getBoolean(AppSettings.KEY_FOLLOW_UP, false) else null
        prefs.edit().putBoolean(AppSettings.KEY_FOLLOW_UP, true).commit()
        oldPaused = AgentRunState.isPaused
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AgentRunState.setPaused(true) }
        ChatStore.init(context)
        awaitUntil { ChatStore.sessions.value.isNotEmpty() }
        originalSession = ChatStore.activeSessionId.value
        session = newSession()
    }

    private fun newSession(): String = ChatStore.newSession().also { id ->
        sessions += id
        awaitUntil { ChatStore.sessions.value.any { it.id == id } }
    }

    @After fun tearDown() {
        if (!::context.isInitialized) return
        if (::originalSession.isInitialized) ChatStore.switchTo(originalSession)
        sessions.forEach { id ->
            PhotoSelectionStore.clear(id)
            if (ChatStore.sessions.value.any { it.id == id }) ChatStore.deleteSession(id)
        }
        val edit = PreferenceManager.getDefaultSharedPreferences(context).edit()
        savedFollowUp?.let { edit.putBoolean(AppSettings.KEY_FOLLOW_UP, it) } ?: edit.remove(AppSettings.KEY_FOLLOW_UP)
        edit.commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AgentRunState.setPaused(oldPaused)
            if (!oldPaused) NotifListener.instance?.onRunStateResumed()
        }
    }

    private val notification = CapturedNotif(
        key = "photo-follow-up-test", packageName = "test.photos", appName = "Messages",
        postTimeMs = 1_700_000_000_000L, title = "Taylor", text = "Can you share the trail photos from yesterday?",
        subText = "", isMessaging = true, isOngoing = false, posted = true, category = "msg",
        actions = listOf(NotifAction(0, "Reply", acceptsText = true)),
    )
    private val decision = ActionDecision(notification.key, ActionDecision.Action.REPLY,
        ActionDecision.Priority.MEDIUM, "The sender requests photos", "Sure, sending them now.", 0.9f, null, "")
    private val hits = (1..3).map { i -> FileIndex.Hit(
        name = "trail-$i.jpg", path = "/storage/emulated/0/Pictures/test-trail-$i.jpg",
        kind = IndexPolicy.Kind.IMAGE, sizeBytes = 100, modifiedMs = 7, snippet = null, capturedAtMs = 1234L,
    ) }

    private fun search(results: List<FileIndex.Hit> = hits, failAfterFirst: Boolean = false): Tool = object : Tool {
        override val name = "search_files"
        override val description = "Scripted actual reader output"
        override val parameters = JSONObject()
        private var count = 0
        override suspend fun invoke(args: JSONObject): String {
            count++
            val photoTurn = requireNotNull(currentCoroutineContext()[PhotoTurn])
            assertNull("A background notification inherited a foreground selection", photoTurn.selectedPhoto)
            if (failAfterFirst && count > 1) return "capability_unavailable: storage access denied"
            photoTurn.showCandidates(results)
            return if (results.isEmpty()) "No files match." else "${results.size} image matches."
        }
    }

    private fun model(script: suspend FlowCollector<AgentEvent>.(AgentSpec, List<ChatMessage>) -> Unit): AiClient =
        object : AiClient {
            override fun run(spec: AgentSpec, history: List<ChatMessage>) = flow { script(spec, history) }
        }

    private suspend fun FlowCollector<AgentEvent>.call(spec: AgentSpec, name: String, args: JSONObject = JSONObject()) {
        val tool = spec.tools.single { it.name == name }
        emit(AgentEvent.ToolCall(name, name, args.toString()))
        tool.onInvocationStarted()
        emit(AgentEvent.ToolResult(name, tool.invokeWithReceipt(args).text))
    }

    private suspend fun FlowCollector<AgentEvent>.prepare(spec: AgentSpec) {
        call(spec, "search_files", JSONObject().put("query", "trail").put("captured_on", "yesterday"))
        call(spec, "suggest_photo_share")
    }

    private suspend fun run(
        client: AiClient, reader: Tool = search(), target: FollowUpSession = FollowUpSession.capture(),
        epoch: Long = DemoReset.generation, timeoutMs: Long = 1_000, incoming: CapturedNotif = notification,
    ) = NotifListener().followUp(incoming, decision, epoch, target, client, listOf(reader), timeoutMs)

    @Test fun automaticSearchPublishesTypedCandidatesAndSourceWithoutAUserMessageOrTextProposal() = runBlocking {
        val result = run(model { spec, history ->
            assertEquals(listOf(FollowUp.SEED), history.map { it.content })
            assertFalse(history.any { it.content.contains(notification.text) })
            assertFalse(spec.tools.any { it.name in setOf("send_photo", "send_sms", "reply_notification", "remember", "forget") })
            // A photo disposition must replace even an earlier proposed text reply.
            call(spec, "draft_notification_reply", JSONObject().put("message", "A premature text draft."))
            prepare(spec)
            assertNull(spec.completionReminder?.invoke())
            assertNull(spec.completionToolChoice?.invoke())
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, "Three matching photos.", actions = listOf("Send now"))))
        })
        assertTrue(result.managesReply)
        assertNull("Photo requests must not open a redundant text confirmation", result.proposal)
        val messages = ChatStore.messages.value
        assertEquals(0, messages.count { it.role == ChatMessage.Role.USER })
        val reply = messages.last()
        assertEquals(hits.map { it.path }, reply.photos.map { it.path })
        assertTrue(reply.photos.all { it.sessionId == session && it.capturedAtMs == 1234L })
        assertTrue(reply.content.contains("Use this photo"))
        assertTrue(reply.actions.isEmpty())
        assertNull(PhotoSelectionStore.selected(session))
        val source = messages.single { it.content.startsWith("[Photo request]") }
        assertEquals(ChatMessage.Role.SYSTEM, source.role)
        assertTrue(source.content.contains(notification.title))
        assertTrue(source.content.contains(notification.text))
    }

    @Test fun switchingChatsDuringSearchKeepsResultsInTheOriginalConversation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val target = FollowUpSession.capture()
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            run(model { spec, _ ->
                entered.complete(Unit)
                proceed.await()
                prepare(spec)
                emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, "Original conversation photos.")))
            }, target = target)
        }
        try {
            entered.await()
            val other = newSession()
            proceed.complete(Unit)
            assertNull(work.await().proposal)
            assertEquals(other, ChatStore.activeSessionId.value)
            assertTrue("Background photos leaked into the newly visible chat", ChatStore.messages.value.isEmpty())
            ChatStore.switchTo(session)
            assertEquals(hits.map { it.path }, ChatStore.messages.value.last().photos.map { it.path })
        } finally { proceed.complete(Unit); work.cancel() }
    }

    @Test fun clearingTheOriginalConversationDuringTheTurnDiscardsTheWholeResult() = runBlocking {
        val result = run(model { spec, _ ->
            prepare(spec)
            ChatStore.clear(session)
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, "Must not return after clear.")))
        })
        assertNull(result.proposal)
        assertTrue(ChatStore.messages.value.isEmpty())
    }

    @Test fun deletingTheOriginalConversationCannotResurrectItOrPublishIntoAnotherChat() = runBlocking {
        val result = run(model { spec, _ ->
            prepare(spec)
            ChatStore.deleteSession(session)
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, "Must not resurrect a deleted chat.")))
        })
        assertNull(result.proposal)
        assertFalse(ChatStore.messages.value.any { it.content.contains("Must not resurrect") || it.photos.isNotEmpty() })
        awaitUntil { ChatStore.sessions.value.none { it.id == session } }
    }

    @Test fun aStaleDemoEpochCannotPublishCandidates() = runBlocking {
        val result = run(model { spec, _ ->
            prepare(spec)
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, "Old demo run.")))
        }, epoch = DemoReset.generation - 1)
        assertNull(result.proposal)
        assertTrue(ChatStore.messages.value.isEmpty())
    }

    @Test fun anErrorAfterSearchDoesNotLeakCandidatesOrAProposal() = runBlocking {
        val result = run(model { spec, _ ->
            prepare(spec)
            emit(AgentEvent.Error(AgentError(AgentError.Kind.NETWORK, "Scripted failure")))
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, "Discard this late answer.")))
        })
        assertNull(result.proposal)
        assertTrue(ChatStore.messages.value.isEmpty())
    }

    @Test fun anErrorMessageInsideDoneCannotBecomeASuccessfulPhotoSuggestion() = runBlocking {
        val result = run(model { spec, _ ->
            prepare(spec)
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, "Scripted error result.", isError = true)))
        })
        assertNull(result.proposal)
        assertTrue(ChatStore.messages.value.isEmpty())
    }

    @Test fun anIncompleteTurnDoesNotPublishItsCandidates() = runBlocking {
        val result = run(model { spec, _ ->
            prepare(spec)
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, "Partial."), isComplete = false))
        })
        assertNull(result.proposal)
        assertTrue(ChatStore.messages.value.isEmpty())
    }

    @Test fun timeoutDiscardsAlreadyFoundCandidates() = runBlocking {
        val result = run(model { spec, _ -> prepare(spec); delay(10_000) }, timeoutMs = 100)
        assertNull(result.proposal)
        assertTrue(ChatStore.messages.value.isEmpty())
    }

    @Test fun noMatchesCanCompleteAsAPhotoSuggestionWithoutForcingATextReply() = runBlocking {
        val result = run(model { spec, _ ->
            call(spec, "search_files", JSONObject().put("query", "trail"))
            assertNull("A zero-result search must leave the response branch to the model", spec.completionToolChoice?.invoke())
            call(spec, "suggest_photo_share")
            assertNull(spec.completionReminder?.invoke())
            assertNull(spec.completionToolChoice?.invoke())
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, FollowUp.NO_SUGGESTION)))
        }, reader = search(emptyList()))
        assertNull(result.proposal)
        val reply = ChatStore.messages.value.last()
        assertTrue(reply.photos.isEmpty())
        assertTrue(reply.content.contains("No photo candidates"))
        assertFalse(reply.content.contains("Use this photo"))
    }

    @Test fun aLaterDeniedSearchDoesNotReuseEarlierCandidates() = runBlocking {
        val result = run(model { spec, _ ->
            prepare(spec)
            call(spec, "search_files", JSONObject().put("query", "a different subject"))
            assertEquals("suggest_photo_share", spec.completionToolChoice?.invoke())
            call(spec, "suggest_photo_share")
            assertNull(spec.completionReminder?.invoke())
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, "Storage access was denied.")))
        }, reader = search(failAfterFirst = true))
        assertNull(result.proposal)
        assertTrue(ChatStore.messages.value.last().photos.isEmpty())
        assertTrue(ChatStore.messages.value.last().content.contains("Storage access was denied"))
    }

    @Test fun aTextAnswerAboutAnImageRemainsATextReplyWhenTheModelChoosesThatBranch() = runBlocking {
        val result = run(model { spec, _ ->
            prepare(spec)
            call(spec, "draft_notification_reply", JSONObject().put("message", "The sign in the photo says Trail closed."))
            assertNull(spec.completionReminder?.invoke())
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, "A text answer is ready.")))
        }, incoming = notification.copy(text = "What does the trail sign say in the picture?"))
        assertNotNull("Image search alone must not route every response to photo sharing", result.proposal)
        assertTrue(result.proposal!!.description.contains("The sign in the photo"))
        assertFalse(result.proposal!!.allowFullAutomation)
        assertTrue("An image used as evidence for a text answer is not a sharing selection",
            ChatStore.messages.value.last().photos.isEmpty())
        assertFalse(ChatStore.messages.value.any { it.content.startsWith("[Photo request]") })
    }

    @Test fun anImageLookupWithoutAShareDispositionKeepsItsOrdinaryTextSuggestion() = runBlocking {
        val result = run(model { spec, _ ->
            call(spec, "search_files", JSONObject().put("query", "trail sign"))
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT,
                "The sign says Trail closed.", actions = listOf("Find another trail"))))
        }, incoming = notification.copy(text = "What does the trail sign say?", actions = emptyList()))
        assertNull(result.proposal)
        val reply = ChatStore.messages.value.last()
        assertTrue(reply.photos.isEmpty())
        assertEquals(listOf("Find another trail"), reply.actions)
        assertFalse(ChatStore.messages.value.any { it.content.startsWith("[Photo request]") })
    }

    @Test fun aMalformedPhotoSuggestionCannotPublishModelSuppliedPathsOrAnOldTextDraft() = runBlocking {
        val result = run(model { spec, _ ->
            call(spec, "draft_notification_reply", JSONObject().put("message", "Old draft."))
            call(spec, "suggest_photo_share", JSONObject().put("path", "/invented/image.jpg"))
            emit(AgentEvent.Done(ChatMessage(ChatMessage.Role.ASSISTANT, "A fabricated image is ready.")))
        })
        assertTrue(result.managesReply)
        assertNull(result.proposal)
        assertTrue(ChatStore.messages.value.isEmpty())
    }
}
