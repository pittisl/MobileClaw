package edu.pitt.isl.mobileclaw.notiftest


import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures notification-posted and notification-removed events, sharing their
 * state through NotifStore for the UI and background assistance.
 *
 * The system binds this service automatically once the user grants notification
 * access via Settings → Notifications → Device & app notifications → Special
 * app access → Notification access. We never start it ourselves.
 */
class NotifListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MobileClaw-NL"

        /** Live listener instance (the service shares the app's process), so agent
         *  action tools can dismiss/reply. Null when notification access isn't connected. */
        @Volatile
        var instance: NotifListener? = null

        /**
         * Our own notifications that are app *machinery*, not content: the agent's status and the confirm-gate prompt. Captured, they would be reasoned
         * about like anything else — the agent would spend an LLM call deciding what to do
         * about being asked a question, and could propose dismissing the notification
         * reporting its own status.
         *
         * Matched by **channel**, not by package. [NotificationTester]'s 1–14 are ours too
         * and are deliberately real input; a package filter would silently disable the
         * whole on-device test harness.
         */
        private val INFRA_CHANNELS = setOf(
            RunStateNotification.CHANNEL_ID,
            NotifConfirm.CHANNEL_ID
        )
    }

    // SupervisorJob means a failed reasoning call doesn't cancel pending ones.
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var reasoningRevision: Long? = null
    private var cachedReasoning: ReasoningModule? = null

    @Synchronized
    private fun reasoningModule(config: LlmSnapshot): ReasoningModule {
        if (reasoningRevision != config.revision || cachedReasoning == null) {
            cachedReasoning = LlmClients.reasoning(config).also { if (DemoReset.hasReset) it.resetForDemo() }
            reasoningRevision = config.revision
        }
        return cachedReasoning!!
    }

    @Volatile
    private var followUpContext = ""
    private val followUpMutex = Mutex()
    private val followUpCooldown = FollowUp.Cooldown()

    // Keys of notifications already present when the listener connected.
    // These are stale — skip reasoning for them. Cleared as they are dismissed
    // so a genuine re-post of the same notification is reasoned about normally.
    private val startupKeys = mutableSetOf<String>()
    private val demoBaseline = DemoEventBaseline()
    @Volatile private var demoResetting = false

    internal fun beginDemoReset() { demoResetting = true }

    internal suspend fun drainDemoRuns() {
        val jobs = serviceScope.coroutineContext[Job]!!.children.toList()
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
    }

    internal fun finishDemoReset() {
        // Do not recapture the old shade. A newer postTime on a reused SMS key is news.
        demoBaseline.reset(runCatching {
            activeNotifications.orEmpty().map { it.key to it.postTime }
        }.getOrDefault(emptyList()))
        startupKeys.clear()
        followUpContext = ""
        followUpCooldown.clear()
        synchronized(this) { cachedReasoning?.resetForDemo() }
        demoResetting = false
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        AppSettings.init(this)
        // The system can bind this service in a process where the user has never opened
        // the Activity, so the run state is read here rather than assumed to be primed.
        AgentRunState.init(this)
        reseedFromActive("Listener connected")
        // "Running" and "running, but access isn't granted" are different answers, and
        // this is the transition between them.
        RunStateNotification.refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        serviceScope.coroutineContext[Job]!!.children.forEach { it.cancel() }
        Log.w(TAG, "Listener disconnected — access may have been revoked")
        RunStateNotification.refresh()
    }

    /**
     * Called when the user resumes the agent. Re-syncs [NotifStore] with what
     * is actually in the shade and marks all of it stale, so nothing that arrived during
     * the pause is reasoned about.
     *
     * That suppression is the point rather than a shortcut. A phone paused overnight has
     * a shade full of notifications the store never saw; reasoning about them on resume
     * would fire a burst of LLM calls — and possibly a burst of confirm notifications —
     * on exactly the tap the user expected to be safe. What they get instead is an
     * accurate picture of *now* with no backlog.
     */
    fun onRunStateResumed() {
        reseedFromActive("Agent resumed")
    }

    /**
     * Rebuilds the store from `activeNotifications`, suppressing reasoning for every key
     * already present. Shared by "the listener just connected" and "the user just
     * resumed", which need identical handling: in both cases the store's picture is out
     * of date and none of what is on screen is news.
     */
    private fun reseedFromActive(reason: String) {
        if (DemoReset.resetting.value) return
        startupKeys.clear()
        if (AgentRunState.isPaused) {
            Log.i(TAG, "$reason — paused, nothing captured")
            return
        }
        activeNotifications?.mapTo(startupKeys) { it.key }
        Log.i(TAG, "$reason — ${startupKeys.size} stale notifications suppressed from reasoning")
        activeNotifications?.forEach { sbn -> handle(sbn, posted = true) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn, posted = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        startupKeys.remove(sbn.key)
        handle(sbn, posted = false)
    }

    private fun handle(sbn: StatusBarNotification, posted: Boolean) {
        val notification = sbn.notification

        // The single chokepoint for both capture and reasoning. Paused means
        // paused: nothing reaches NotifStore either, so a notification that arrives while
        // the agent is off is not filed away for the next chat turn to hand to the model
        // through buildNotifContext.
        if (AgentRunState.isPaused || demoResetting || DemoReset.resetting.value) return
        if (demoBaseline.shouldIgnore(sbn.key, sbn.postTime, posted)) return

        // Our own machinery — the status notification and the confirm prompt — is not
        // content to triage. See INFRA_CHANNELS for why this matches on channel rather
        // than on package.
        if (sbn.packageName == packageName && notification.channelId in INFRA_CHANNELS) return
        // The ranker's auto-generated group summary (tag `ranker_group`) carries no text of
        // its own once an app has posted enough notifications; triaging it is an LLM call
        // spent on an empty record. Summaries *with* text ("3 new messages") still go
        // through — some apps put real content there.
        if (notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) {
            val ex = notification.extras
            val summaryTitle = ex?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val summaryText = ex?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (summaryTitle.isBlank() && summaryText.isBlank()) {
                Log.d(TAG, "skip empty group summary ${sbn.key}")
                return
            }
        }

        val extras = notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()
        
        val category = notification.category.orEmpty()
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val color = notification.color
        val tickerText = notification.tickerText?.toString().orEmpty()
        val badgeCount = notification.number
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progressCurrent = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val isProgressIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        // Capture actions with their index and whether they accept inline text
        // (a RemoteInput). We deliberately don't store the PendingIntent — it isn't
        // serializable and can go stale; at click time we re-resolve the live action
        // from activeNotifications by key + title/index.
        val actions = notification.actions?.mapIndexed { i, a ->
            NotifAction(
                index = i,
                title = a.title?.toString().orEmpty(),
                acceptsText = a.remoteInputs?.isNotEmpty() == true
            )
        } ?: emptyList()

        // v2: Unpack MessagingStyle using NotificationCompat for better reliability.
        // This extracts chat history (sender + text) often hidden in EXTRA_MESSAGES.
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val isMessaging = messagingStyle != null

        val displayBody = if (isMessaging && messagingStyle != null) {
            messagingStyle.messages.joinToString("\n") { m ->
                val sender = m.person?.name ?: m.sender ?: "Someone"
                "$sender: ${m.text}"
            }
        } else {
            text.ifEmpty { bigText }
        }

        // Extract visual assets
        val visualAssets = mutableMapOf<String, Bitmap>()

        // 1. App Icon
        try {
            drawableToBitmap(packageManager.getApplicationIcon(sbn.packageName))?.let {
                visualAssets["app_icon"] = it
            }
        } catch (e: Exception) {}

        // 2. Small Icon
        notification.smallIcon?.loadDrawable(this)?.let { drawableToBitmap(it) }?.let {
            visualAssets["small_icon"] = it
        }

        // 3. Large Icon
        (extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap)?.let {
            visualAssets["large_icon"] = it
        } ?: (extras.get(Notification.EXTRA_LARGE_ICON) as? Icon)?.loadDrawable(this)?.let { drawableToBitmap(it) }?.let {
            visualAssets["large_icon"] = it
        }

        // 4. Big Picture
        (extras.get(Notification.EXTRA_PICTURE) as? Bitmap)?.let {
            visualAssets["big_picture"] = it
        }

        // 5. Messaging Avatars
        messagingStyle?.messages?.forEachIndexed { index, m ->
            m.person?.icon?.loadDrawable(this)?.let { drawableToBitmap(it) }?.let {
                visualAssets["avatar_$index"] = it
            }
            
            // 6. Messaging Images (Inline)
            if (m.dataMimeType?.startsWith("image/") == true && m.dataUri != null) {
                try {
                    contentResolver.openInputStream(m.dataUri!!).use { stream ->
                        BitmapFactory.decodeStream(stream)?.let { 
                            visualAssets["msg_img_$index"] = it
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load message image: ${m.dataUri}", e)
                }
            }
        }

        val captured = CapturedNotif(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            postTimeMs = sbn.postTime,
            title = title,
            text = displayBody,
            subText = subText,
            summaryText = summaryText,
            infoText = infoText,
            isMessaging = isMessaging,
            isOngoing = sbn.isOngoing,
            posted = posted,
            category = category,
            template = template,
            color = color,
            tickerText = tickerText,
            badgeCount = badgeCount,
            progressMax = progressMax,
            progressCurrent = progressCurrent,
            isProgressIndeterminate = isProgressIndeterminate,
            actions = actions,
            visualAssets = visualAssets
        )

        Log.d(TAG, captured.toLogLine())
        NotifStore.add(captured)

        // Only reason about genuinely new notifications — not removals or stale startup ones.
        if (posted && captured.key !in startupKeys) {
            val epoch = DemoReset.generation
            val target = FollowUpSession.capture()
            serviceScope.launch {
                val config = LlmConfig.snapshot()
                val decision = reasoningModule(config).decide(captured) ?: return@launch
                currentCoroutineContext().ensureActive()
                Log.i(TAG, "Decision [${captured.appName}]: ${decision.action} / ${decision.priority} — ${decision.reasoning}")
                target.publish(epoch) { surfaceDecision(captured, decision, target.id) }
                // Contextual replies replace the unverified triage draft. A skipped,
                // failed or incomplete contextual turn must not revive that draft.
                val followUp = followUp(captured, decision, epoch, target, config = config)
                currentCoroutineContext().ensureActive()
                target.publish(epoch) {
                    if (followUp.managesReply) followUp.proposal?.let { PendingAction.propose(it) }
                    else execute(captured, decision)
                }
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Posts a brief summary of the AI's decision to the chat panel so the user
     * can see what the background reasoning loop is doing. Skipped for do_nothing
     * decisions to avoid spamming the chat with low-priority noise.
     */
    private fun surfaceDecision(notif: CapturedNotif, decision: ActionDecision, sessionId: String) {
        if (decision.action == ActionDecision.Action.DO_NOTHING && decision.priority == ActionDecision.Priority.LOW) return

        val msg = buildString {
            append("[Auto] ${notif.appName}")
            if (notif.title.isNotBlank()) append(": ${notif.title}")
            append("\nAction: ${decision.action} (${decision.priority})")
            if (decision.reasoning.isNotBlank()) append("\n${decision.reasoning}")
            if (decision.action == ActionDecision.Action.REPLY && decision.replyText.isNotBlank() &&
                !(AppSettings.followUp && NotificationReplyDraft.eligible(notif, decision))) {
                append("\nReply: \"${decision.replyText}\"")
            }
            if (decision.action == ActionDecision.Action.CLICK_ACTION && decision.actionTitle.isNotBlank()) {
                append("\nButton: \"${decision.actionTitle}\"")
            }
        }
        // SYSTEM role keeps these out of the AI's JSON conversation turns
        ChatStore.add(sessionId, ChatMessage(role = ChatMessage.Role.SYSTEM, content = msg))
    }

    /**
     * The follow-up turn (policy in [FollowUp]): one bounded run of the chat's
     * tool loop over a read-only catalog, seeded with the triaged notification through the
     * client's context block, whose answer lands in the chat as an assistant message with
     * action chips. Tapping a chip sends it as the user's next message, so any action it
     * leads to runs through the chat loop and [PendingAction] like every other.
     *
     * One at a time, and a second qualifying notification that arrives mid-turn is
     * skipped rather than queued: a burst of notifications is the case where a queue of
     * follow-ups would be a queue of LLM calls the user did not ask for. Then at most one
     * per app per [FollowUp.COOLDOWN_MS], checked under the lock so a
     * notification skipped for the lock does not spend the app's window.
     *
     * Readers may be joined by one turn-local draft buffer, bound to this notification.
     * It cannot send. Only a completed turn can produce a confirmation proposal; failures
     * and skipped duplicate replies never fall back to the unverified triage draft.
     */
    internal data class FollowUpResult(
        val managesReply: Boolean = false,
        val proposal: PendingAction.Proposal? = null,
    )

    // Client/catalog injection exercises this exact runner offline; defaults are the
    // production client and registry. Admission, context and publishing are unchanged.
    internal suspend fun followUp(
        notif: CapturedNotif,
        decision: ActionDecision,
        epoch: Long,
        target: FollowUpSession = FollowUpSession.capture(),
        client: AiClient? = null,
        catalog: List<Tool> = ToolRegistry.all(),
        timeoutMs: Long? = null,
        config: LlmSnapshot = LlmConfig.snapshot(),
    ): FollowUpResult {
        if (!AppSettings.followUp || !FollowUp.shouldRun(notif, decision)) return FollowUpResult()
        val draft = if (NotificationReplyDraft.eligible(notif, decision))
            NotificationReplyDraft(NotificationContextRequirements.readersFor("${notif.title}\n${notif.text}")) else null
        // A notification is not permission to reuse an earlier foreground selection.
        val photos = PhotoTurn(target.id, selectedPhoto = null)
        val photoSuggestion = NotificationPhotoSuggestion(photos, draft)
        fun withheld() = FollowUpResult(managesReply = draft != null || photoSuggestion.requested)
        if (!followUpMutex.tryLock()) {
            Log.i(TAG, "Follow-up already running — skipping ${notif.appName}")
            return withheld()
        }
        try {
            if (!followUpCooldown.tryStart(notif.packageName, SystemClock.elapsedRealtime())) {
                Log.i(TAG, "Follow-up for ${notif.appName} skipped: one ran in the last ${FollowUp.COOLDOWN_MS / 60_000} min")
                return withheld()
            }
            followUpContext = FollowUp.contextBlock(notif, decision)
            val readers = FollowUp.readOnlyTools(catalog).map(photoSuggestion::tracking)
            val baseSpec = FollowUp.spec(readers + listOfNotNull(photoSuggestion.textReplyTool(), photoSuggestion), config.profile.model)
            val spec = baseSpec.copy(
                systemPrompt = baseSpec.systemPrompt + "\n\n" + photos.modelContext(),
                completionReminder = { photoSuggestion.completionReminder() },
                completionToolChoice = { photoSuggestion.nextRequiredTool() },
            )
            val seed = listOf(ChatMessage(role = ChatMessage.Role.USER, content = FollowUp.SEED))
            val consulted = mutableListOf<String>()
            var done: AgentEvent.Done? = null
            var failed = false
            val turnTimeoutMs = timeoutMs ?: maxOf(FollowUp.TIMEOUT_MS, config.profile.readTimeoutSeconds * 1_000 + 10_000)
            val finished = withTimeoutOrNull(turnTimeoutMs) {
                withContext(photos) {
                    (client ?: LlmClients.chat(config) { followUpContext }).run(spec, seed).collect { event ->
                        when (event) {
                            is AgentEvent.ToolCall -> {
                                consulted += event.name
                            }
                            is AgentEvent.Done -> {
                                done = event
                            }
                            is AgentEvent.Error -> {
                                failed = true
                                Log.w(TAG, "Follow-up failed: ${event.error.message}")
                            }
                            else -> Unit
                        }
                    }
                }
            } != null
            if (!finished) Log.w(TAG, "Follow-up timed out after $turnTimeoutMs ms for ${notif.appName}")
            currentCoroutineContext().ensureActive()
            if (photoSuggestion.requested && !photoSuggestion.resolved) return withheld()
            val base = FollowUp.completedMessage(done, finished, failed, hasDraft = draft?.message != null)
            val complete = finished && !failed && done?.isComplete == true && done?.message?.isError == false
            val rendered = if (complete && photoSuggestion.resolved) photoSuggestion.render(base, notif)
                else base
            if (rendered == null) {
                Log.i(TAG, "Follow-up for ${notif.appName}: nothing to add (consulted ${consulted.ifEmpty { listOf("nothing") }})")
                return withheld()
            }
            // One event line, not one per tool call: SYSTEM lines fold into the next chat
            // turn's system prompt (the last eight), and a chatty follow-up would push the
            // [Auto] decisions out of that window.
            val published = target.publish(epoch) {
                if (consulted.isNotEmpty()) {
                    ChatStore.add(target.id, ChatMessage(
                        role = ChatMessage.Role.SYSTEM,
                        content = "[Follow-up] ${notif.appName}: consulted ${consulted.distinct().joinToString(", ")}"
                    ))
                }
                if (photoSuggestion.resolved) {
                    ChatStore.add(target.id, ChatMessage(ChatMessage.Role.SYSTEM, FollowUp.photoRequestContext(notif)))
                }
                ChatStore.add(target.id, rendered)
            }
            if (!published) return withheld()
            val label = notif.appName + if (notif.title.isNotBlank()) " — \"${ReasoningModule.redact(notif.title)}\"" else ""
            return FollowUpResult(draft != null || photoSuggestion.requested, draft?.proposal(label) { text ->
                // Re-resolve exactly this notification version at approval time. A newer
                // message reusing the conversation key needs its own context and consent.
                executeReply(notif.key, text, expectedPostTime = notif.postTimeMs)
            })
        } finally {
            followUpMutex.unlock()
        }
    }

    /**
     * Routes an auto-decided action through the SAME confirm-before-acting gate
     * the chat tools use: rather than acting directly, it stages a [PendingAction]
     * so the user must explicitly approve (or reject) before anything outward or
     * irreversible happens (dismissing, sending a reply, pressing a button).
     *
     * If the app isn't in the foreground there's no dialog to confirm against, so
     * the proposal simply isn't acted on — fail-safe: the agent never acts on a
     * notification behind the user's back.
     */
    private fun execute(notif: CapturedNotif, decision: ActionDecision) {
        val key = decision.notificationKey
        val label = notif.appName +
            if (notif.title.isNotBlank()) " — \"${ReasoningModule.redact(notif.title)}\"" else ""

        when (decision.action) {
            ActionDecision.Action.DISMISS -> {
                Log.d(TAG, "Proposing DISMISS for $key")
                PendingAction.propose(
                    PendingAction.Proposal(
                        description = "Dismiss notification: $label",
                        execute = { dismissByKey(key) }
                    )
                )
            }
            ActionDecision.Action.REPLY -> {
                if (decision.replyText.isBlank()) return
                Log.d(TAG, "Proposing REPLY for $key")
                PendingAction.propose(
                    PendingAction.Proposal(
                        description = "Reply to $label: \"${decision.replyText}\"",
                        execute = { replyByKey(key, decision.replyText) }
                    )
                )
            }
            ActionDecision.Action.CLICK_ACTION -> {
                if (decision.actionTitle.isBlank()) return
                Log.d(TAG, "Proposing CLICK_ACTION for $key: \"${decision.actionTitle}\"")
                PendingAction.propose(
                    PendingAction.Proposal(
                        description = "Press \"${decision.actionTitle}\" on $label",
                        execute = { clickActionByKey(key, decision.actionTitle) }
                    )
                )
            }
            else -> Log.d(TAG, "No automated execution for action: ${decision.action}")
        }
    }

    // ---- Agent-invoked actions (Action Module). Called from tools via the static
    // ---- [instance]; user confirmation is handled upstream by PendingAction. ----

    fun dismissByKey(key: String): String {
        cancelNotification(key)
        Log.i(TAG, "Dismissed $key")
        return "Notification dismissed."
    }

    fun replyByKey(key: String, text: String): String = executeReply(key, text)

    /**
     * Triggers an arbitrary notification button by title — the generalised form of
     * [executeReply]. "Clicking a button" means firing the button's PendingIntent
     * ([Notification.Action.actionIntent]), which runs as the originating app with
     * its identity/permissions, exactly as if the user had tapped it in the shade.
     * No special permission beyond notification access is required.
     *
     * If the matched action carries a RemoteInput and [inputText] is provided, the
     * text is filled in (inline reply); otherwise the action is triggered bare.
     * Every invocation — success or failure — is recorded in [ActionLogStore].
     */
    fun clickActionByKey(key: String, actionTitle: String, inputText: String? = null): String {
        fun fail(reason: String, appName: String = ""): String {
            ActionLogStore.record(appName, key, actionTitle, inputText, reason, success = false)
            return reason
        }

        val sbn = activeNotifications?.find { it.key == key }
            ?: return fail("Click failed — that notification is no longer present.")

        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val notifActions = sbn.notification.actions
        if (notifActions.isNullOrEmpty()) {
            return fail("Click failed — this notification has no buttons.", appName)
        }
        val action = notifActions.firstOrNull {
            it.title?.toString().equals(actionTitle, ignoreCase = true)
        } ?: return fail(
            "Click failed — no \"$actionTitle\" button (available: ${
                notifActions.mapNotNull { it.title?.toString() }.joinToString(", ")
            }).", appName
        )

        // Some actions ship without a PendingIntent (e.g. handled purely in-app);
        // there's nothing to trigger, so fail gracefully rather than NPE.
        val actionIntent = action.actionIntent
            ?: return fail("Click failed — the \"$actionTitle\" button has no action to trigger.", appName)

        val acceptsText = action.remoteInputs?.isNotEmpty() == true
        return try {
            if (acceptsText && inputText != null) {
                actionIntent.send(this, 0, fillRemoteInput(action.remoteInputs!!, inputText))
            } else {
                actionIntent.send(this, 0, android.content.Intent())
            }
            val result = "Pressed \"$actionTitle\"."
            ActionLogStore.record(appName, key, actionTitle, inputText.takeIf { acceptsText }, result, success = true)
            result
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "Click PendingIntent cancelled for $key (\"$actionTitle\")", e)
            fail("Click failed — the app cancelled the action.", appName)
        }
    }

    private fun executeReply(notificationKey: String, replyText: String, expectedPostTime: Long? = null): String {
        if (replyText.isBlank()) {
            Log.w(TAG, "Reply: skipping — reply text is empty")
            return "Can't reply — the message is empty."
        }
        val sbn = activeNotifications?.find { it.key == notificationKey } ?: run {
            Log.w(TAG, "Reply: notification not found for key $notificationKey")
            return "Reply failed — that notification is no longer present."
        }
        if (expectedPostTime != null && sbn.postTime != expectedPostTime) {
            return "Reply not sent — the notification changed. Review the latest message before replying."
        }
        // Match by RemoteInput rather than title: some apps use a title-less inline
        // reply action, so the presence of a RemoteInput is the reliable signal.
        val replyAction = sbn.notification.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        } ?: run {
            Log.w(TAG, "Reply: no RemoteInput action found on notification")
            return "Reply failed — this notification has no inline reply field."
        }
        val actionTitle = replyAction.title?.toString().orEmpty().ifEmpty { "Reply" }
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        return try {
            replyAction.actionIntent.send(this, 0, fillRemoteInput(replyAction.remoteInputs!!, replyText))
            Log.i(TAG, "Reply sent to $notificationKey: \"$replyText\"")
            ActionLogStore.record(appName, notificationKey, actionTitle, replyText, "Reply sent.", success = true)
            "Reply sent."
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "Reply PendingIntent cancelled for $notificationKey", e)
            val result = "Reply failed — the app cancelled the action."
            ActionLogStore.record(appName, notificationKey, actionTitle, replyText, result, success = false)
            result
        }
    }

    /** Builds the fill-in Intent that carries [text] into every RemoteInput of an action. */
    private fun fillRemoteInput(
        remoteInputs: Array<android.app.RemoteInput>,
        text: String
    ): android.content.Intent {
        val resultBundle = Bundle()
        for (ri in remoteInputs) {
            resultBundle.putCharSequence(ri.resultKey, text)
        }
        return android.content.Intent().also {
            android.app.RemoteInput.addResultsToIntent(remoteInputs, it, resultBundle)
        }
    }
}

/**
 * One actionable button on a notification. [index] and [title] let a downstream
 * consumer (the LLM, the action tools) name a specific button; [acceptsText]
 * flags actions that carry a RemoteInput (inline reply / text entry) so callers
 * know whether supplying text is meaningful.
 */
data class NotifAction(
    val index: Int,
    val title: String,
    val acceptsText: Boolean
)

/**
 * Plain data class representing a single capture event. Keep this flat and
 * serializable-friendly — down the line it's the natural schema to hand off
 * to the reasoning/LLM layer (via Room, a ContentProvider, or Binder).
 */
data class CapturedNotif(
    val key: String,
    val packageName: String,
    val appName: String,
    val postTimeMs: Long,
    val title: String,
    val text: String,
    val subText: String,
    val summaryText: String = "",
    val infoText: String = "",
    val isMessaging: Boolean,
    val isOngoing: Boolean,
    val posted: Boolean, // true = onNotificationPosted, false = onNotificationRemoved
    val category: String = "",
    val template: String = "",
    val color: Int = 0,
    val tickerText: String = "",
    val badgeCount: Int = 0,
    val progressCurrent: Int = 0,
    val progressMax: Int = 0,
    val isProgressIndeterminate: Boolean = false,
    val actions: List<NotifAction> = emptyList(),
    val visualAssets: Map<String, Bitmap> = emptyMap()
) {
    fun toLogLine(): String {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(postTimeMs))
        val verb = if (posted) "POST" else "REMOVE"
        val flags = buildString {
            if (isMessaging) append(" [msg]")
            if (isOngoing) append(" [ongoing]")
            if (category.isNotEmpty()) append(" [$category]")
            if (template.isNotEmpty()) append(" [style:${template.substringAfterLast("$")}]")
            if (progressMax > 0) append(" [progress:$progressCurrent/$progressMax]")
            if (isProgressIndeterminate) append(" [progress:indeterminate]")
            if (badgeCount > 0) append(" [badge:$badgeCount]")
            if (actions.isNotEmpty()) append(" [actions:${actions.joinToString(",") { it.title }}]")
            if (visualAssets.isNotEmpty()) append(" [assets:${visualAssets.keys.joinToString(",")}]")
        }
        val cleanText = text.replace("\n", " | ")
        return "$verb $stamp $appName ($packageName)$flags :: $title — $cleanText"
    }

    /**
     * Returns this capture as a JSONObject. Use [toJSON] for a serialized
     * string, or have callers (e.g. NotifStore) compose it into a larger
     * structure without an extra parse round-trip.
     */
    fun toJSONObject(): JSONObject = JSONObject().apply {
        put("key", key)
        put("packageName", packageName)
        put("appName", appName)
        put("postTime", SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(postTimeMs)))
        put("postTimeMs", postTimeMs)
        put("title", title)
        put("text", text)
        put("subText", subText)
        put("summaryText", summaryText)
        put("infoText", infoText)
        put("isMessaging", isMessaging)
        put("isOngoing", isOngoing)
        put("posted", posted)
        put("category", category)
        put("template", template)
        put("color", String.format("#%06X", 0xFFFFFF and color))
        put("tickerText", tickerText)
        put("badgeCount", badgeCount)
        if (progressMax > 0 || isProgressIndeterminate) {
            put("progress", JSONObject().apply {
                put("current", progressCurrent)
                put("max", progressMax)
                put("indeterminate", isProgressIndeterminate)
            })
        }
        put("actions", JSONArray().apply {
            actions.forEach { a ->
                put(JSONObject().apply {
                    put("index", a.index)
                    put("title", a.title)
                    put("acceptsText", a.acceptsText)
                })
            }
        })

        val assetsObj = JSONObject()
        visualAssets.forEach { (name, bitmap) ->
            assetsObj.put(name, bitmapToBase64(bitmap))
        }
        put("visualAssets", assetsObj)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    fun toJSON(): String = toJSONObject().toString()
}
