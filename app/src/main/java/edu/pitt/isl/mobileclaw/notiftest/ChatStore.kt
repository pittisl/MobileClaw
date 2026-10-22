package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Chat history, shared between the UI and the agent loop. In-memory for the live view,
 * persisted to Room so a conversation survives a process death.
 *
 * **Only settled turns are written.** A turn is added to memory the moment it exists, but
 * reaches disk only via [add] (user/system turns, which are complete on arrival) or
 * [finalize] (the assistant turn, once streaming has stopped). [replaceAt] — which the
 * streaming loop calls ~20× a second — is deliberately memory-only. That keeps
 * token-by-token updates off the disk entirely, and means a crash mid-stream can't restore
 * a half-written bubble or a pending placeholder that will never complete.
 *
 * Writes are funnelled through a single-consumer [Channel] rather than launched ad hoc, so
 * they commit in the order they were made.
 *
 * ### Sessions
 *
 * This owns a lazily-materialised `Map<sessionId, MutableStateFlow<List<ChatMessage>>>`,
 * and [messages] is whichever of those is **active**. Three invariants apply:
 *
 *  - **A turn is addressed by [Slot], not by an index.** The streaming loop adds a
 *    placeholder and rewrites it ~20×/second; with more than one conversation, a bare index
 *    is a promise that the user will not switch sessions mid-reply. They will, and the
 *    rewrite would land in whatever conversation they switched to. A `Slot` carries the
 *    session it belongs to, so a reply always finishes in the conversation it was asked in
 *    — visible again when the user switches back, and never grafted onto another.
 *  - **Background writers post to the active session.** `NotifListener`, `PendingAction`,
 *    `NotifConfirm`, `OverlayConfirm` and `TestActionReceiver` all call [add] with no
 *    session in hand, because they are reporting on something the user just approved or
 *    triggered. The active id is therefore persisted in [SharedPreferences] rather than
 *    held in memory: those callers run in a process that may have been started by the
 *    notification itself, and it has to reach the same answer the UI would. Same reasoning
 *    as [AgentRunState], for the same reason — a cold process must not guess.
 *  - **[MAX_ENTRIES] is per session**, so switching does not evict what the other
 *    conversation was holding. Only sessions the user has actually opened are materialised;
 *    the rest cost a row in [ChatSessionDao] and nothing else.
 */
object ChatStore {

    private const val TAG = "MobileClaw-CS"

    /**
     * Maximum messages retained in memory per session and made available to the agent
     * through [MainViewModel]. [HistoryBudget] separately bounds the request token estimate;
     * the durable history has its own [MAX_ROWS] limit.
     */
    private const val MAX_ENTRIES = 100

    /** Durable cap — the full record the user can export, independent of what's in the prompt. */
    private const val MAX_ROWS = 5_000

    private val RETENTION_MS = TimeUnit.DAYS.toMillis(90)

    private const val PURGE_INTERVAL = 50

    const val DEFAULT_SESSION = "default"

    /**
     * What a session row carries when nobody chose an agent — the id of the default one,
     * resolved at access rather than frozen, for the reason [AgentRegistry.generalChat]'s
     * KDoc gives.
     */
    val DEFAULT_AGENT_SPEC: String get() = AgentRegistry.generalChat.id

    /**
     * Legacy stored agent id. [AgentRegistry.resolve] falls back for this unresolved
     * value, preserving the conversation without requiring a row rewrite.
     */
    const val LEGACY_AGENT_SPEC = "default"

    /** Survives process death, because background writers read it from a cold process. */
    private const val KEY_ACTIVE_SESSION = "chat_active_session"

    /** How much of the first user turn becomes a session's automatic name. */
    private const val TITLE_CHARS = 40

    /** Where a turn lives: which conversation, and where in it. */
    data class Slot(val sessionId: String, val index: Int)

    /** A conversation, as the switcher shows it. */
    data class Session(
        val id: String,
        val title: String?,
        val createdMs: Long,
        val lastActiveMs: Long,
        /**
         * Which agent answers in here. Stored as written, **not** resolved: this is a
         * record of what was chosen, and collapsing an unknown id into the default one here
         * would lose the difference between "runs on the default agent" and "asked for an
         * agent this build doesn't have". [AgentRegistry.resolve] is where that collapse
         * belongs, at the point of use.
         */
        val agentSpecId: String
    ) {
        /** Never blank: an unnamed session is still something the user has to pick out. */
        val displayTitle: String
            get() = title?.takeIf { it.isNotBlank() }
                ?: if (id == DEFAULT_SESSION) "Chat" else "Untitled chat"
    }

    private val perSession = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()

    /**
     * Which agent each conversation runs on, readable synchronously.
     *
     * [sessions] cannot answer this at the moment it matters most. The row for a session
     * created a moment ago is still queued on the write channel, so a user who types
     * straight into a new conversation would find nothing and get the default agent — the
     * exact bug this indirection exists to prevent. [newSession] records the choice here
     * before it returns; the DAO flow keeps it true from then on.
     */
    private val agentSpecIds = mutableMapOf<String, String>()

    private val _activeSessionId = MutableStateFlow(DEFAULT_SESSION)
    val activeSessionId: StateFlow<String> = _activeSessionId

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    /**
     * The active conversation. A separate flow rather than the map's, so a collector bound
     * once in [MainActivity] keeps working across a switch instead of having to resubscribe
     * — and so the agent loop reading `messages.value` always means "the conversation on
     * screen", which is what every existing caller assumed when there was only one.
     */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writes = Channel<suspend (ChatMessageDao) -> Unit>(Channel.UNLIMITED)

    @Volatile
    private var dao: ChatMessageDao? = null

    @Volatile
    private var sessionDao: ChatSessionDao? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    private var insertsSincePurge = 0

    @Synchronized
    fun init(context: Context) {
        if (dao != null) return
        val db = MobileClawDb.get(context)
        val d = db.chatMessages()
        val s = db.chatSessions()
        dao = d
        sessionDao = s
        val p = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        prefs = p
        _activeSessionId.value = p.getString(KEY_ACTIVE_SESSION, DEFAULT_SESSION) ?: DEFAULT_SESSION

        scope.launch {
            // Restore first, so a turn recorded during startup lands after history rather
            // than being overwritten by it.
            try {
                val now = System.currentTimeMillis()
                // The default session is a row like any other from here on; without this a
                // fresh install has messages in a conversation the switcher can't see.
                s.insertIfAbsent(
                    ChatSessionEntity(DEFAULT_SESSION, null, now, now, DEFAULT_AGENT_SPEC)
                )
                val active = _activeSessionId.value
                val activeRow = s.find(active)
                if (activeRow == null) {
                    // The active session was deleted by another process, or the preference
                    // outlived its row. Fall back rather than write into a session that no
                    // longer exists.
                    setActive(DEFAULT_SESSION)
                } else {
                    // Ahead of the DAO flow, and on purpose: this is the one session the
                    // user can type into before it emits, so it is the one whose agent has
                    // to be known first.
                    synchronized(ChatStore) { agentSpecIds[active] = activeRow.agentSpecId }
                }
                val restored = d.recent(_activeSessionId.value, MAX_ENTRIES)
                    .asReversed().map { it.toMessage() }
                if (restored.isNotEmpty()) {
                    synchronized(ChatStore) {
                        val flow = flowFor(_activeSessionId.value)
                        flow.value = (restored + flow.value).takeLast(MAX_ENTRIES)
                        _messages.value = flow.value
                    }
                    Log.i(TAG, "Restored ${restored.size} chat messages")
                }
                purge(d)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore chat history", e)
            }
            // Single consumer: writes commit in submission order.
            for (write in writes) {
                try {
                    write(d)
                } catch (e: Exception) {
                    Log.w(TAG, "Chat write failed", e)
                }
            }
        }

        scope.launch {
            s.observeAll().collect { rows ->
                synchronized(ChatStore) {
                    // Upserted, not rebuilt: a session created since this query ran is not
                    // in `rows` yet, and dropping it here would reintroduce the window the
                    // map exists to close.
                    rows.forEach { agentSpecIds[it.sessionId] = it.agentSpecId }
                }
                _sessions.value = rows.map {
                    Session(it.sessionId, it.title, it.createdMs, it.lastActiveMs, it.agentSpecId)
                }
            }
        }
    }

    /** The flow for [sessionId], created empty on first use. Callers hold the lock. */
    private fun flowFor(sessionId: String): MutableStateFlow<List<ChatMessage>> =
        perSession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

    /**
     * Appends [m] to the active session and returns where it landed. Persisted unless it is
     * a pending placeholder or an error bubble.
     */
    fun add(m: ChatMessage): Slot = add(_activeSessionId.value, m)

    @Synchronized
    fun add(sessionId: String, m: ChatMessage): Slot {
        val flow = flowFor(sessionId)
        val next = (flow.value + m).takeLast(MAX_ENTRIES)
        flow.value = next
        if (sessionId == _activeSessionId.value) _messages.value = next
        persist(sessionId, m)
        return Slot(sessionId, next.lastIndex)
    }

    /**
     * Replaces the message at [slot] **in memory only** — this is the streaming path,
     * called once per UI flush. Use [finalize] for the last write of a turn.
     */
    @Synchronized
    fun replaceAt(slot: Slot, m: ChatMessage) {
        val flow = perSession[slot.sessionId] ?: return
        val current = flow.value
        if (slot.index !in current.indices) return
        val next = current.toMutableList().also { it[slot.index] = m }
        flow.value = next
        if (slot.sessionId == _activeSessionId.value) _messages.value = next
    }

    /**
     * Replaces the message at [slot] and commits it — the terminal write of an agent turn.
     * Error bubbles are shown but not stored: they carry a retry affordance that means
     * nothing after a restart.
     */
    @Synchronized
    fun finalize(slot: Slot, m: ChatMessage) {
        replaceAt(slot, m)
        persist(slot.sessionId, m)
    }

    /** Clears one conversation on disk and in memory; defaults to the visible session. */
    @Synchronized
    fun clear(id: String = _activeSessionId.value) {
        PhotoSelectionStore.clear(id)
        flowFor(id).value = emptyList()
        if (_activeSessionId.value == id) _messages.value = emptyList()
        writes.trySend { d -> d.deleteSession(id) }
    }

    // ---- sessions ----------------------------------------------------------------

    /**
     * Creates a conversation and makes it active. Returns its id.
     *
     * [agentSpecId] is taken as given rather than validated: the registry is a runtime
     * thing and a caller that has one in hand got it from there. An id that stops
     * resolving later is handled where it is read, not by refusing to create the
     * conversation now.
     */
    @Synchronized
    fun newSession(agentSpecId: String = DEFAULT_AGENT_SPEC): String {
        val now = System.currentTimeMillis()
        val id = "s-$now-${(0..999).random()}"
        val s = sessionDao
        // Queued on the write channel rather than launched, so it lands *before* any
        // message written into this session. Launched separately, a user who types
        // immediately races the session row into existence and their first turn finds
        // nothing to name or to touch — a conversation that stays "Untitled chat" forever
        // and sorts to the bottom of the switcher.
        writes.trySend { _ -> s?.upsert(ChatSessionEntity(id, null, now, now, agentSpecId)) }
        agentSpecIds[id] = agentSpecId
        flowFor(id)
        setActive(id)
        return id
    }

    /**
     * Which agent [sessionId] runs on, or null if this process has never heard of it.
     *
     * Returns the id **as stored**; call [AgentRegistry.resolve] on it to get a spec.
     *
     * Null means "not known in this process yet", which is a real state and not the same
     * fact as "runs on the default agent": between process start and the first DAO
     * emission, a session read back from disk is unknown for a moment. [AgentRegistry.resolve]
     * turns both into the default agent anyway, deliberately — the alternative is blocking
     * a turn on SQLite to avoid a window that [init] already closes for the one session
     * that can be typed into.
     */
    @Synchronized
    fun agentSpecIdFor(sessionId: String): String? = agentSpecIds[sessionId]

    /**
     * Switches the visible conversation, loading it from disk the first time.
     *
     * The load is asynchronous and the flow is swapped immediately, so an unopened session
     * shows empty for the moment it takes to read — the alternative is blocking the UI
     * thread on SQLite to avoid a flicker nobody has reported.
     */
    fun switchTo(sessionId: String) {
        val alreadyLoaded = synchronized(this) {
            val known = perSession.containsKey(sessionId)
            setActive(sessionId)
            known
        }
        if (!alreadyLoaded) {
            scope.launch {
                val d = dao ?: return@launch
                val rows = try {
                    d.recent(sessionId, MAX_ENTRIES).asReversed().map { it.toMessage() }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load session $sessionId", e); return@launch
                }
                synchronized(ChatStore) {
                    val flow = flowFor(sessionId)
                    // Anything written while the read was in flight wins: those turns are
                    // newer than the page we just read.
                    if (flow.value.isEmpty()) flow.value = rows
                    if (_activeSessionId.value == sessionId) _messages.value = flow.value
                }
            }
        }
    }

    /**
     * Deletes a conversation and its turns.
     *
     * The last session is never deleted, and deleting the active one moves to the next: an
     * app with no conversation has nowhere to put the next background message, and the
     * background writers have no way to ask for one.
     */
    @Synchronized
    fun deleteSession(sessionId: String) {
        if (_sessions.value.size <= 1) {
            clear()
            return
        }
        PhotoSelectionStore.clear(sessionId)
        perSession.remove(sessionId)
        agentSpecIds.remove(sessionId)
        val s = sessionDao
        // Both on the same channel, turns first: one ordering domain for everything that
        // touches this session on disk.
        writes.trySend { d -> d.deleteSession(sessionId) }
        writes.trySend { _ -> s?.delete(sessionId) }
        if (_activeSessionId.value == sessionId) {
            val next = _sessions.value.firstOrNull { it.id != sessionId }?.id ?: DEFAULT_SESSION
            switchTo(next)
        }
    }

    fun rename(sessionId: String, title: String) {
        val s = sessionDao ?: return
        val trimmed = title.trim().take(TITLE_CHARS * 2)
        if (trimmed.isEmpty()) return
        scope.launch { s.rename(sessionId, trimmed) }
    }

    @Synchronized
    private fun setActive(sessionId: String) {
        _activeSessionId.value = sessionId
        _messages.value = perSession[sessionId]?.value.orEmpty()
        // commit(), not apply(): the reader may be a BroadcastReceiver in a process that
        // dies as soon as onReceive returns, and a background message filed into the wrong
        // conversation is not something the user can find again.
        prefs?.edit()?.putString(KEY_ACTIVE_SESSION, sessionId)?.commit()
        sessionDao?.let { s -> scope.launch { s.touch(sessionId, System.currentTimeMillis()) } }
    }

    private fun persist(sessionId: String, m: ChatMessage) {
        if (m.pending || m.isError) return
        val row = m.toEntity(sessionId)
        val shouldPurge = ++insertsSincePurge >= PURGE_INTERVAL
        if (shouldPurge) insertsSincePurge = 0
        val s = sessionDao
        writes.trySend { d ->
            d.insert(row)
            s?.touch(sessionId, System.currentTimeMillis())
            // The first thing the user said is what the conversation is about, which is a
            // better name than a timestamp and costs no LLM call. `nameIfUnnamed` makes it
            // impossible for this to overwrite a title they chose.
            if (m.role == ChatMessage.Role.USER) {
                s?.nameIfUnnamed(sessionId, m.content.trim().take(TITLE_CHARS).ifEmpty { "Chat" })
            }
            if (shouldPurge) purge(d)
        }
    }

    private suspend fun purge(d: ChatMessageDao) {
        val byAge = d.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
        val byCount = d.trimToNewest(MAX_ROWS)
        if (byAge > 0 || byCount > 0) {
            Log.i(TAG, "Chat retention sweep: dropped $byAge aged + $byCount overflow rows")
        }
    }

    private fun ChatMessage.toEntity(sessionId: String) = ChatMessageEntity(
        sessionId = sessionId,
        role = role.name,
        content = content,
        timestampMs = timestampMs,
        actions = ChatConverters.fromActions(actions)
    )

    private fun ChatMessageEntity.toMessage() = ChatMessage(
        role = runCatching { ChatMessage.Role.valueOf(role) }.getOrDefault(ChatMessage.Role.SYSTEM),
        content = content,
        timestampMs = timestampMs,
        pending = false,
        isError = false,
        actions = ChatConverters.toActions(actions)
    )
}
