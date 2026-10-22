package edu.pitt.isl.mobileclaw.notiftest

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import edu.pitt.isl.mobileclaw.notiftest.appfunctions.AppFunctionPing
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var chatList: RecyclerView
    private lateinit var chatInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var emptyState: View
    private lateinit var chatAdapter: ChatAdapter

    private val viewModel: MainViewModel by viewModels()

    private var demoResetProgress: AlertDialog? = null
    private var photoPreview: AlertDialog? = null
    private val actionDialogs = mutableSetOf<AlertDialog>()
    private lateinit var tester: NotificationTester

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // The capability whose runtime permission is currently being requested, so the
    // result callback can report which one succeeded/failed.
    private var pendingCapability: Capability? = null
    private val capabilityPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        val cap = pendingCapability
        pendingCapability = null
        val label = cap?.label ?: "Permission"
        Toast.makeText(
            this,
            if (isGranted) "$label granted" else "$label denied",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tester = NotificationTester(this)
        AppSettings.init(this)
        AgentRunState.init(this)
        checkNotificationPermission()

        findViewById<View>(R.id.btnHistory).setOnClickListener {
            startActivity(Intent(this, ActionLogActivity::class.java))
        }

        findViewById<View>(R.id.btnMore).setOnClickListener { view -> showOverflowMenu(view) }

        setupRunStateBar()
        setupChatPanel()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(ChatStore.activeSessionId, PhotoSelectionStore.changes) { id, _ -> id }
                    .collect { renderSelectedPhoto(it) }
            }
        }

        // Run state → the status strip. Collected rather than set once, because the
        // sticky notification's Pause button can change it while this screen is open.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentRunState.paused.collect { renderRunState(it) }
            }
        }

        // Whether an LLM is reachable → the same strip. A key can be rejected while this
        // screen is open (by a chat turn or by a background notification), and the strip
        // is the only place the second case can be seen at all.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LlmConfig.status.collect { renderRunState(AgentRunState.isPaused) }
            }
        }

        // Chat stream → lower-half RecyclerView.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChatStore.messages.collect { messages ->
                    // An empty timeline is an invitation, not a blank panel — and a
                    // screenshot of a blank panel is a wasted figure.
                    emptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                    chatAdapter.submitList(messages) {
                        if (messages.isNotEmpty()) {
                            chatList.scrollToPosition(messages.size - 1)
                        }
                    }
                }
            }
        }

        // The switcher's label. Both flows feed it: the active id changes on a switch, and
        // the session list changes when the first user turn names the conversation — a
        // label bound to only the id would keep saying "Chat" after it had been renamed.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(ChatStore.activeSessionId, ChatStore.sessions) { id, sessions ->
                    sessions.firstOrNull { it.id == id }
                }.collect { renderSessionSwitcher(it) }
            }
        }

        // Running state (owned by the ViewModel) → send/stop buttons.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.running, viewModel.resetting) { running, resetting -> running to resetting }
                    .collect { (running, resetting) ->
                        setRunning(running)
                        chatInput.isEnabled = !resetting
                        if (resetting) {
                            btnSend.isEnabled = false
                            btnStop.visibility = View.GONE
                            actionDialogs.toList().forEach { it.dismiss() }
                            if (demoResetProgress == null) {
                                demoResetProgress = MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle(R.string.menu_reset_demo)
                                    .setMessage(R.string.demo_reset_progress)
                                    .setCancelable(false).show()
                            }
                        } else {
                            demoResetProgress?.dismiss()
                            demoResetProgress = null
                        }
                    }
            }
        }

        // Completion is a counter: an idle reset can begin and finish in one UI tick,
        // so relying on observing both edges of a boolean would lose its feedback.
        var lastReset = viewModel.demoResetCompleted.value
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.demoResetCompleted.collect { completed ->
                    if (completed > lastReset) {
                        lastReset = completed
                        actionDialogs.toList().forEach { it.dismiss() }
                        chatInput.setText("")
                        Toast.makeText(this@MainActivity, R.string.demo_reset_done, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        var lastResetFailure = viewModel.demoResetFailed.value
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.demoResetFailed.collect { failed ->
                    if (failed > lastResetFailure) {
                        lastResetFailure = failed
                        Toast.makeText(this@MainActivity, R.string.demo_reset_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // A tool hit a missing capability → walk the user through granting it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PermissionSignal.requests.collect { cap -> promptForCapability(cap) }
            }
        }

        // A tool proposed an action (reply/dismiss) → confirm before doing it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PendingAction.proposals.collect { proposal ->
                    if (!PendingAction.isCurrent(proposal)) {
                        proposal.onDiscard?.invoke()
                        return@collect
                    }
                    var resolved = false
                    // MaterialAlertDialogBuilder, not appcompat's: only this one reads
                    // materialAlertDialogTheme, which is what inverts the card. The gate is
                    // the most consequential moment in the interface and the one place the
                    // design spends its contrast.
                    val builder = MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(if (proposal.imagePreviewPath != null) "Send photo?" else "Confirm action")
                        .setMessage(proposal.description)
                        .setPositiveButton(if (proposal.imagePreviewPath != null) "Send photo" else "Do it") { _, _ ->
                            resolved = true
                            lifecycleScope.launch {
                                PendingAction.executeAndPublish(proposal)
                            }
                        }
                        .setNegativeButton("Not now") { _, _ ->
                            resolved = true
                            PendingAction.decline(proposal)
                        }
                        .setOnCancelListener {
                            resolved = true
                            PendingAction.decline(proposal)
                        }
                    val preview = proposal.imagePreviewPath?.let {
                        ImageView(this@MainActivity).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            contentDescription = "Photo to send"
                            setPadding(dp(20), dp(8), dp(20), dp(8))
                        }.also { image ->
                            builder.setView(LinearLayout(this@MainActivity).apply { addView(image) })
                        }
                    }
                    val dialog = builder.create()
                    actionDialogs.add(dialog)
                    dialog.setOnDismissListener {
                        actionDialogs.remove(dialog)
                        // Programmatic dismissal (rotation/reset) does not invoke cancel.
                        // A positive click has already handed ownership to execution.
                        if (!resolved) proposal.onDiscard?.invoke()
                    }
                    dialog.show()
                    if (preview != null) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        lifecycleScope.launch {
                            val bitmap = withContext(Dispatchers.IO) {
                                runCatching { PhotoImages.decode(proposal.imagePreviewPath!!, 1000) }.getOrNull()
                            }
                            if (dialog.isShowing) {
                                preview.setImageBitmap(bitmap)
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = bitmap != null && PendingAction.isCurrent(proposal)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        photoPreview?.dismiss()
        photoPreview = null
        demoResetProgress?.dismiss()
        demoResetProgress = null
        actionDialogs.toList().forEach { it.dismiss() }
        super.onDestroy()
    }

    /**
     * The in-app half of the run-state control; the sticky notification is
     * the other half, for when the app is closed. Both drive the same persisted flag,
     * so they can't disagree.
     */
    private fun setupRunStateBar() {
        findViewById<View>(R.id.runStateChip).setOnClickListener {
            val nowPaused = !AgentRunState.isPaused
            AgentRunState.setPaused(nowPaused)
            // Re-sync the store with the shade on resume without reasoning about any of
            // it — see NotifListener.onRunStateResumed.
            if (!nowPaused) NotifListener.instance?.onRunStateResumed()
            RunStateNotification.refresh()
        }
    }

    /**
     * Developer and secondary controls live in the overflow menu to keep the main
     * screen focused on the conversation. The notification test menu remains available
     * for demonstrations.
     */
    private fun showOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(getString(R.string.menu_settings)).setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java)); true
            }
            menu.add(getString(R.string.menu_memory)).setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, MemoryActivity::class.java)); true
            }
            menu.addSubMenu(getString(R.string.menu_test)).apply {
                add("1 · Direct message").setOnMenuItemClickListener { tester.fireDirectMessage(); true }
                add("2 · Group chat").setOnMenuItemClickListener { tester.fireGroupChat(); true }
                add("3 · CI failure").setOnMenuItemClickListener { tester.fireCiFailure(); true }
                add("4 · File sync (progress)").setOnMenuItemClickListener { tester.fileSyncProgress(); true }
                add("5 · Payment sent").setOnMenuItemClickListener { tester.firePaymentSent(); true }
                add("6 · Media playback").setOnMenuItemClickListener { tester.fireMediaPlayback(); true }
                add("7 · Photo tag").setOnMenuItemClickListener { tester.firePhotoTag(); true }
                add("8 · Email digest").setOnMenuItemClickListener { tester.fireEmailDigest(); true }
                add("9 · OTP code").setOnMenuItemClickListener { tester.fireOtp(); true }
                add("10 · Missed calls (×3)").setOnMenuItemClickListener { tester.fireMissedCalls(); true }
                add("11 · Class reminder").setOnMenuItemClickListener { tester.fireClassReminder(); true }
                add("12 · Repo starred").setOnMenuItemClickListener { tester.fireRepoStarred(); true }
                add("13 · Actionable email (clickable)").setOnMenuItemClickListener { tester.fireActionableEmail(); true }
                add("14 · Confirm request (clickable)").setOnMenuItemClickListener { tester.fireConfirmRequest(); true }
                add("15 · Hostile instruction").setOnMenuItemClickListener { tester.fireHostileInstruction(); true }
                add("16 · Card number + forward").setOnMenuItemClickListener { tester.fireSensitiveForward(); true }
                // Not a notification: a round trip over the platform's AppFunctions bus to
                // our own `ping`. Lives in this menu because it is a demo
                // affordance, and its result lands in the chat as a system line.
                add("AF · AppFunctions ping").setOnMenuItemClickListener { runAppFunctionPing(); true }
            }
            menu.add(getString(R.string.menu_reset_demo)).apply {
                isEnabled = !viewModel.resetting.value
                setOnMenuItemClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.menu_reset_demo)
                        .setMessage(R.string.demo_reset_message)
                        .setPositiveButton(R.string.demo_reset_confirm) { _, _ -> viewModel.resetDemo() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
            }
            menu.add(getString(R.string.menu_clear_notifs)).setOnMenuItemClickListener {
                NotifStore.clear(); true
            }
            show()
        }
    }

    /**
     * Says what the agent is actually doing, which is more states than two: paused,
     * running-but-deaf (no notification access, so nothing is captured however willing
     * the agent is), running-but-mute (no usable API key, so everything is captured and
     * nothing is reasoned about), and running. Collapsing any of the middle ones into
     * "running" would report the app as working while it silently does nothing.
     */
    private fun renderRunState(paused: Boolean) {
        findViewById<android.widget.TextView>(R.id.runStateText).text =
            getString(if (paused) R.string.state_paused else R.string.state_running)
        val dot = findViewById<View>(R.id.runStateDot)
        dot.setBackgroundResource(if (paused) R.drawable.dot_paused else R.drawable.dot_running)
        animateRunStateDot(dot, paused)
        findViewById<View>(R.id.runStateChip).contentDescription =
            getString(if (paused) R.string.action_resume else R.string.action_pause)
        refreshSetupStrip(paused)
    }

    /**
     * The dot breathes while the agent is running and holds still when it is paused.
     *
     * This is the only continuous evidence on screen that an autonomous process exists —
     * everything else about the background loop is, by construction, invisible until it
     * decides something. Stillness carrying the paused state is the point: the absence of
     * motion is the signal, so pausing is legible from across a room.
     *
     * The animator is stored on the view itself rather than in a field, because this runs
     * on every state change and on every `onResume`, and an animator left running under a
     * replacement is a leak that shows up as a dot beating at two rates.
     */
    private fun animateRunStateDot(dot: View, paused: Boolean) {
        (dot.getTag(R.id.tag_dot_animator) as? android.animation.Animator)?.cancel()
        dot.alpha = 1f
        if (paused || !Motion.enabled(this)) {
            dot.setTag(R.id.tag_dot_animator, null)
            return
        }
        val anim = android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f).apply {
            duration = Motion.BREATH / 2
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = Motion.SMOOTH
            start()
        }
        dot.setTag(R.id.tag_dot_animator, anim)
    }

    /**
     * The setup strip shows what is preventing the agent from working and opens the
     * corresponding setup flow. It disappears when nothing needs attention.
     *
     * Its ordering matches the sticky notification: notification access comes before
     * model configuration because capture cannot work without access. Both surfaces
     * must report the same setup state.
     */
    private fun refreshSetupStrip(paused: Boolean) {
        val strip = findViewById<View>(R.id.setupStrip)
        val text = findViewById<android.widget.TextView>(R.id.setupText)
        val action = findViewById<Button>(R.id.btnSetupAction)

        // Paused is a deliberate state, not a problem to fix. Telling someone who just
        // pressed Pause that notification access is off answers a question they did not
        // ask.
        if (paused) { strip.visibility = View.GONE; return }

        val problem: Triple<String, String, () -> Unit>? = when {
            !isNotificationAccessGranted() -> Triple(
                getString(R.string.setup_notifications), getString(R.string.setup_grant)
            ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

            LlmConfig.status.value == LlmConfig.Status.NOT_CONFIGURED -> Triple(
                "Configure a model service to enable the assistant", getString(R.string.menu_settings)
            ) { startActivity(Intent(this, ModelServiceActivity::class.java)) }

            LlmConfig.status.value == LlmConfig.Status.REJECTED -> Triple(
                "The LLM key was rejected, so nothing is triaged", getString(R.string.menu_settings)
            ) { startActivity(Intent(this, ModelServiceActivity::class.java)) }

            !Settings.canDrawOverlays(this) -> Triple(
                getString(R.string.setup_overlay), getString(R.string.setup_grant)
            ) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")
                ))
            }

            else -> null
        }

        if (problem == null) {
            strip.visibility = View.GONE
        } else {
            strip.visibility = View.VISIBLE
            text.text = problem.first
            action.text = problem.second
            action.setOnClickListener { problem.third() }
        }
    }

    /**
     * Onboards a single [Capability]: explains why it's needed and offers a one-tap
     * grant path — a runtime permission dialog for dangerous permissions, or a
     * deep-link to the right Settings page for special-access ones. If it's already
     * granted, nothing is shown.
     */
    private fun promptForCapability(cap: Capability) {
        if (cap.isGranted(this)) return

        // The odd one out: there is no permission to grant and no Settings page to open.
        // A privileged backend is set up outside the app, so the two failure modes need
        // different answers — "Shizuku is running, allow us" is a real prompt, while
        // "Shizuku isn't installed" is not something a button here can fix.
        if (cap == Capability.PRIVILEGED_SHELL) {
            promptForPrivilegedShell(cap)
            return
        }

        val runtimePermission = cap.manifestPermission
        val onGrant: () -> Unit = if (runtimePermission != null) {
            {
                pendingCapability = cap
                capabilityPermissionLauncher.launch(runtimePermission)
            }
        } else {
            { startActivity(specialAccessIntent(cap)) }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("${cap.label} needed")
            .setMessage(cap.rationale)
            .setPositiveButton(if (runtimePermission != null) "Grant" else "Open settings") { _, _ -> onGrant() }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** Shared with Settings, so the two screens cannot describe different grant paths. */
    private fun promptForPrivilegedShell(cap: Capability) = ShizukuOnboarding.prompt(this, cap)

    /** Deep-link to the correct Settings page for a special-access capability. */
    /**
     * Self-call over the AppFunctions bus, reported as a system line in the chat. API 36+
     * only; on anything older the line says so rather than pretending.
     */
    private fun runAppFunctionPing() {
        lifecycleScope.launch {
            val text = when (val o = AppFunctionPing.run(this@MainActivity)) {
                is AppFunctionPing.Outcome.Pong -> "AppFunctions: ping → \"${o.reply}\" via the platform service in ${o.elapsedMs} ms"
                is AppFunctionPing.Outcome.Failed -> "AppFunctions: ping failed — ${o.reason}"
                AppFunctionPing.Outcome.Unsupported -> "AppFunctions: needs Android 16 (API 36); this device is API ${android.os.Build.VERSION.SDK_INT}"
            }
            ChatStore.add(ChatMessage(role = ChatMessage.Role.SYSTEM, content = text))
        }
    }

    private fun specialAccessIntent(cap: Capability): Intent = when (cap) {
        Capability.NOTIFICATION_ACCESS ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        Capability.USAGE_ACCESS ->
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        Capability.OVERLAY ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        Capability.ALL_FILES_ACCESS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        else ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    }

    /**
     * "Allow app-open" — requests the Display-over-other-apps (SYSTEM_ALERT_WINDOW)
     * permission. Holding it exempts us from background-activity-start limits, so
     * notification buttons that *open an app* (Open / Join) fire reliably when the
     * agent presses them. Pure background buttons work without it. Reflects the
     * current grant state in the label.
     */
    /** True when this app is in the system's enabled notification-listener set. */
    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any {
            it.isNotEmpty() && ComponentName.unflattenFromString(it)?.packageName == packageName
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have toggled a permission in Settings and come back — which is
        // exactly what the setup strip reports, and renderRunState refreshes it.
        renderRunState(AgentRunState.isPaused)
        // The user may also have granted POST_NOTIFICATIONS, which the sticky
        // notification needs and silently gave up on at startup without.
        RunStateNotification.refresh()
    }

    private fun setupChatPanel() {
        chatList = findViewById(R.id.chatList)
        chatInput = findViewById(R.id.chatInput)
        btnSend = findViewById(R.id.btnSend)
        btnStop = findViewById(R.id.btnStop)
        emptyState = findViewById(R.id.emptyState)

        chatAdapter = ChatAdapter(
            onActionClick = { source, action -> viewModel.submitAction(source, action) },
            onRetryClick  = { msg -> viewModel.retry(msg) },
            onPhotoClick  = { photo -> showPhotoPreview(photo) }
        )
        chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatList.adapter = chatAdapter
        // Arrivals rise into place, so cause and effect stay visible when a notification
        // is fired in front of an audience. Change animations stay off — see the class.
        chatList.itemAnimator = TimelineItemAnimator()

        btnSend.setOnClickListener { submitCurrentInput() }
        btnStop.setOnClickListener { viewModel.cancel() }

        chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submitCurrentInput(); true } else false
        }

        // Clears only this conversation. The dedicated switcher opens the session menu;
        // long-pressing this control does not open another navigation surface.
        findViewById<View>(R.id.btnChatClear).setOnClickListener { ChatStore.clear() }

        findViewById<TextView>(R.id.sessionSwitcher).setOnClickListener { showSessionMenu(it) }

        setupQuickActions()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun renderSelectedPhoto(sessionId: String) {
        val selected = PhotoSelectionStore.selected(sessionId)
        findViewById<View>(R.id.selectedPhotoBar).visibility = if (selected == null) View.GONE else View.VISIBLE
        findViewById<View>(R.id.clearSelectedPhoto).setOnClickListener { PhotoSelectionStore.deselect(sessionId) }
        if (selected != null) {
            findViewById<TextView>(R.id.selectedPhotoName).text = "Selected photo\n${selected.name}"
            PhotoImages.load(findViewById(R.id.selectedPhotoThumbnail), selected.path)
        }
    }

    private fun showPhotoPreview(candidate: PhotoCandidate) {
        if (candidate.sessionId != ChatStore.activeSessionId.value ||
            candidate.generation != PhotoSelectionStore.generation(candidate.sessionId)) {
            Toast.makeText(this, "Search for the photo again in this conversation.", Toast.LENGTH_SHORT).show()
            return
        }
        photoPreview?.dismiss()
        val builder = MaterialAlertDialogBuilder(this)
        val image = ImageView(builder.context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Photo preview: ${candidate.name}"
        }
        val content = LinearLayout(builder.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(image)
            addView(TextView(builder.context).apply {
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface))
                text = candidate.capturedAtMs?.let {
                    "Captured " + java.text.SimpleDateFormat("MMM d, yyyy · HH:mm", java.util.Locale.US).format(java.util.Date(it))
                } ?: "Capture date unknown"
            })
        }
        val dialog = builder
            .setTitle("Preview photo")
            .setView(content)
            .setPositiveButton("Use this photo", null)
            .setNegativeButton("Back", null)
            .create()
        photoPreview = dialog
        dialog.setOnDismissListener { if (photoPreview === dialog) photoPreview = null }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        var previewFingerprint: PhotoFingerprint? = null
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshot = PhotoSelectionStore.preview(candidate)
                    PhotoImages.decode(snapshot.bytes, 1200) to snapshot.fingerprint
                }.getOrNull()
            }
            if (!dialog.isShowing) return@launch
            previewFingerprint = loaded?.second
            image.setImageBitmap(loaded?.first)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = loaded?.first != null
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            it.isEnabled = false
            lifecycleScope.launch {
                try {
                    PhotoSelectionStore.select(candidate, requireNotNull(previewFingerprint))
                    dialog.dismiss()
                    chatInput.requestFocus()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, e.message ?: "Photo unavailable.", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
            }
        }
    }

    /**
     * The switcher's label: which agent is answering, and in which conversation.
     *
     * The agent name is shown even though there is only one agent to show. That is the
     * purpose of resolving the agent for display — the screen names what the turn will actually run on, so
     * a session whose stored agent no longer resolves reads as the default agent here
     * rather than looking identical to one that chose it. It goes through
     * [AgentRegistry.resolve] for exactly that reason: the label must agree with
     * [MainViewModel], and two callers deriving the fallback separately is how they stop
     * agreeing.
     */
    private fun renderSessionSwitcher(session: ChatStore.Session?) {
        val agent = AgentRegistry.resolve(session?.agentSpecId)
        // A session auto-titled from its first user turn can be a whole sentence, and the
        // switcher is set as an uppercase letter-spaced eyebrow, which makes a long one
        // considerably worse — on a device it wrapped to two lines and pushed the
        // timeline down. Bounded here as well as by maxLines in the layout: ellipsizing
        // mid-word is a rendering fallback, not a label.
        val title = (session?.displayTitle ?: "Chat").let {
            if (it.length <= TITLE_MAX) it else it.take(TITLE_MAX).trimEnd().trimEnd(',', '.') + "…"
        }
        findViewById<TextView>(R.id.sessionSwitcher).text = "${agent.displayName} · $title ▾"
    }

    /**
     * The conversation switcher.
     *
     * Deliberately built from what the store already exposes rather than from a new
     * ViewModel surface: sessions are a property of the store, and every other screen that
     * reads one — the memory screen, the action log — does it the same way.
     */
    private fun showSessionMenu(anchor: View) {
        val sessions = ChatStore.sessions.value
        val active = ChatStore.activeSessionId.value
        PopupMenu(this, anchor).apply {
            // No agent picker here, deliberately: `AgentRegistry` has one entry, and a
            // submenu offering a choice of one would be chrome describing a capability the
            // app does not have yet. `newSession` already takes the id — adding a second
            // agent is what turns this into a picker, not a further refactor.
            menu.add("New conversation").setOnMenuItemClickListener {
                ChatStore.newSession(); true
            }
            sessions.forEach { s ->
                // The tick is the only thing telling the user which conversation they are
                // already in; without it, switching to the current one looks like a no-op
                // bug rather than a no-op.
                val mark = if (s.id == active) "✓ " else "   "
                menu.add("$mark${s.displayTitle}").setOnMenuItemClickListener {
                    if (s.id != active) ChatStore.switchTo(s.id)
                    true
                }
            }
            if (sessions.size > 1) {
                menu.add("Delete this conversation").setOnMenuItemClickListener {
                    confirmDeleteSession(active); true
                }
            }
            show()
        }
    }

    /**
     * Deleting a conversation destroys stored turns, so it asks — the same rule the memory
     * wipe and "Clear agent history" follow. Named, because "this conversation" in a dialog
     * that covers the screen is not enough to tell two of them apart.
     */
    private fun confirmDeleteSession(sessionId: String) {
        val name = ChatStore.sessions.value.firstOrNull { it.id == sessionId }?.displayTitle
            ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"$name\"?")
            .setMessage("Permanently deletes this conversation and its stored messages. Other conversations are unaffected.")
            .setPositiveButton("Delete") { _, _ -> ChatStore.deleteSession(sessionId) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupQuickActions() {
        mapOf(
            R.id.chipSummarize to "Summarize my current notifications.",
            R.id.chipUrgent    to "Which of my notifications need immediate attention?",
            R.id.chipMessages  to "Do I have any unread messages or chats?",
            R.id.chipDismiss   to "Which notifications can I safely dismiss right now?",
            R.id.chipCatchUp   to "Give me a quick overview of everything I missed."
        ).forEach { (id, prompt) ->
            findViewById<View>(id).setOnClickListener { viewModel.submit(prompt) }
        }
    }

    private fun submitCurrentInput() {
        val text = chatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        chatInput.setText("")
        viewModel.submit(text)
    }

    private fun setRunning(running: Boolean) {
        btnSend.isEnabled = !running
        btnStop.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }


    private companion object {
        /** Longest session title the switcher will show before eliding. */
        const val TITLE_MAX = 24
    }
}
