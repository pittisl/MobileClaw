package edu.pitt.isl.mobileclaw.notiftest

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import edu.pitt.isl.mobileclaw.notiftest.skills.search.SearchRendering
import edu.pitt.isl.mobileclaw.notiftest.skills.search.SearchIndexWorker
import edu.pitt.isl.mobileclaw.notiftest.skills.search.IndexState
import android.os.Build
import androidx.preference.ListPreference
import androidx.appcompat.app.AppCompatDelegate
import android.os.Looper
import android.os.Handler
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Hosts the app's preference screen (`res/xml/preferences.xml`) using the Android
 * Jetpack Preference library, so settings are stored in the default
 * SharedPreferences and read back via [AppSettings]. Opened from the "⋯" overflow
 * menu on the main screen.
 *
 * A "Permissions & Capabilities" section is built in code (one row per
 * [Capability]) with a live granted/not-granted status and a one-tap grant path —
 * a runtime permission dialog or a deep-link to the right Settings page.
 * Above it, a read-only "Skills" section (one row per [Skill]) says what the agent can
 * currently do and which capability each ability is waiting on.
 *
 * The "Data" section owns [ActionLogActivity], [DataExport] and the audit-log wipe —
 * view, copy, delete, in that order. All three are deliberately here and nowhere else:
 * they are user-initiated by design and must stay outside the agent's reach (see
 * [DataExport]).
 *
 * "Memory" is a separate category above it, holding [MemoryActivity] and its own wipe.
 * Separate rather than a fourth row under Data, because the two destructive actions are
 * opposites and must not be confusable: clearing agent history deletes the record of what
 * the agent *did*, while this deletes what the user asked it to *keep*.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // Secondary screens run under a NoActionBar theme, so nothing supplied an up
        // affordance and the system gesture was the only way out — fine if you know it,
        // a dead end if you do not, which is every visitor handed the phone at a booth.
        findViewById<android.widget.TextView>(R.id.headerTitle).text = "Settings"
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        // One Preference per capability, kept so summaries can refresh on resume.
        private val capabilityPrefs = mutableMapOf<Capability, Preference>()

        // Same, per skill — a skill's readiness is derived from its capabilities, so it
        // has to be recomputed whenever those change.
        private val skillPrefs = mutableMapOf<Skill, Preference>()
        private var searchIndexPref: Preference? = null

        private var pendingCapability: Capability? = null
        private val runtimePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            refreshCapabilityStatuses()
            refreshSkillStatuses()
            refreshCalendarSources()
        }

        // Destination picked by the user via SAF — no storage permission needed, and
        // the app never chooses where their data lands.
        private val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument(DataExport.MIME_TYPE)
        ) { uri -> if (uri != null) runExport(uri) }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("model_service")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ModelServiceActivity::class.java))
                true
            }
            refreshModelService()

            // Apply the theme on the next frame. Returning true persists the preference, so
            // reading it or recreating the Activity before this callback returns would race
            // that write. Read through AppSettings, the same source used at cold start.
            findPreference<ListPreference>(AppSettings.KEY_THEME)
                ?.setOnPreferenceChangeListener { _, _ ->
                    Handler(Looper.getMainLooper()).post {
                        AppCompatDelegate.setDefaultNightMode(AppSettings.nightMode)
                    }
                    true
                }

            buildMemorySection()
            buildDataSection()
            buildSkillSection()
            buildSearchSection()
            buildCapabilitySection()
            refreshCalendarSources()
        }

        private fun refreshModelService() {
            val pref = findPreference<Preference>("model_service") ?: return
            val profile = LlmConfig.snapshot().profile
            pref.summary = when {
                LlmConfig.storageError != null -> getString(R.string.model_service_storage_error)
                !profile.isConfigured -> getString(R.string.model_service_setup)
                else -> getString(R.string.model_service_active,
                    ModelServiceActivity.providerLabel(requireContext(), profile.provider), profile.model)
            }
        }

        private fun refreshCalendarSources() {
            val pref = findPreference<ListPreference>(AppSettings.KEY_CALENDAR_SOURCE) ?: return
            val labels = mutableListOf<CharSequence>("All calendars (read only)")
            val values = mutableListOf<CharSequence>("all")
            val writableIds = mutableSetOf<String>()
            if (Capability.READ_CALENDAR.isGranted(requireContext())) {
                requireContext().contentResolver.query(android.provider.CalendarContract.Calendars.CONTENT_URI,
                    arrayOf("_id", "calendar_displayName", "account_name", "calendar_access_level"), null, null, "_id ASC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0).toString()
                        val writable = cursor.getInt(3) >= android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                        if (writable) writableIds += id
                        labels += "${cursor.getString(1) ?: "Unnamed calendar"} (${cursor.getString(2)})" +
                            if (writable) "" else " — read only"
                        values += id
                    }
                }
            }
            val selected = pref.value ?: "all"
            if (selected !in values) {
                labels += "Unavailable calendar — choose another calendar"
                values += selected
            }
            pref.entries = labels.toTypedArray()
            pref.entryValues = values.toTypedArray()
            pref.summaryProvider = Preference.SummaryProvider<ListPreference> {
                when {
                    it.value == null || it.value == "all" ->
                        "Read all calendars. Choose one writable calendar to add events."
                    it.value !in writableIds ->
                        "${it.entry}. Choose a writable calendar to add events."
                    else -> "${it.entry}. Used for reading and adding events. Additions require confirmation."
                }
            }
        }

        /**
         * What the agent can currently do, one row per [Skill]. Above the
         * capability list on purpose: a permission is a means, and "Notification access
         * is not granted" doesn't tell anyone which of the agent's abilities just went
         * dark. Read-only — granting still happens in the section below, where the
         * one-tap paths live.
         */
        private fun buildSkillSection() {
            SkillRegistry.init(requireContext())
            val category = PreferenceCategory(requireContext()).apply {
                title = "Skills"
                isIconSpaceReserved = false
            }
            preferenceScreen.addPreference(category)

            SkillRegistry.all().forEach { skill ->
                val pref = Preference(requireContext()).apply {
                    title = skill.displayName
                    isIconSpaceReserved = false
                    isSelectable = false
                }
                category.addPreference(pref)
                skillPrefs[skill] = pref
            }
            refreshSkillStatuses()
        }

        /**
         * The one control the file index needs: rebuild now. Indexing is otherwise a
         * background job, and the row's summary is how a user learns whether
         * the index exists at all — the tool says the same thing to the model.
         */
        private fun buildSearchSection() {
            val category = PreferenceCategory(requireContext()).apply {
                title = "File search"
                isIconSpaceReserved = false
            }
            preferenceScreen.addPreference(category)
            // Photo analysis has its own setting because it runs models over files and the
            // ML Kit dependency sends usage telemetry. Either setting change rebuilds the
            // index: enabling owes features and disabling must remove stored features.
            category.addPreference(SwitchPreferenceCompat(requireContext()).apply {
                key = AppSettings.KEY_LABEL_PHOTOS
                title = "Index photos and PDFs by content"
                summary = "Run the on-device classifier, image-similarity and text-recognition models over photos " +
                    "and PDFs while indexing, so the assistant can find them by what they show (cat, dog…), by a " +
                    "short description, or by the text printed on them (a boarding pass, a receipt). " +
                    "Off: they are searchable by name only."
                setDefaultValue(true)
                isIconSpaceReserved = false
                setOnPreferenceChangeListener { _, _ ->
                    SearchIndexWorker.runNow(requireContext())
                    true
                }
            })
            searchIndexPref = Preference(requireContext()).apply {
                title = "Rebuild search index now"
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    SearchIndexWorker.runNow(requireContext())
                    Toast.makeText(requireContext(), "Rebuilding the file index in the background", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            category.addPreference(searchIndexPref!!)
            refreshSearchStatus()
        }

        private fun refreshSearchStatus() {
            val ctx = context ?: return
            val state = IndexState.read(ctx)
            // Coming back from the all-files grant page is the moment the first build is
            // owed; the periodic job looked before the grant and will not look again soon.
            if (state.needsBuild && Capability.ALL_FILES_ACCESS.isGranted(ctx)) SearchIndexWorker.runNow(ctx)
            searchIndexPref?.summary = SearchRendering.describe(state).removePrefix("Index status: ")
        }

        private fun buildMemorySection() {
            findPreference<Preference>("memory_review")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), MemoryActivity::class.java)); true
            }
            findPreference<Preference>("clear_memory")?.setOnPreferenceClickListener {
                confirmClearMemory(); true
            }
        }

        /**
         * Wiping memory is its own action with its own confirm, and is deliberately NOT
         * part of "Clear agent history".
         *
         * The two look similar and are opposites: the audit trail is the record a user may
         * well want gone, while memory is the one thing in the app they explicitly asked to
         * keep. Folding this into that button would delete it without ever asking.
         */
        private fun confirmClearMemory() {
            val count = MemoryStore.memories.value.size
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Forget everything?")
                .setMessage(
                    "Permanently deletes all $count stored ${if (count == 1) "memory" else "memories"}, " +
                        "including the ones you asked the agent to remember. The agent will " +
                        "start future conversations knowing nothing about you.\n\n" +
                        "This cannot be undone, and it is separate from clearing agent history."
                )
                .setPositiveButton("Forget everything") { _, _ ->
                    MemoryStore.clear()
                    Toast.makeText(requireContext(), "All memories deleted", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun buildDataSection() {
            findPreference<Preference>("action_history")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ActionLogActivity::class.java)); true
            }
            findPreference<Preference>("export_data")?.setOnPreferenceClickListener {
                confirmExport(); true
            }
            findPreference<Preference>("clear_action_log")?.setOnPreferenceClickListener {
                confirmClearActionLog(); true
            }
        }

        /**
         * The export leaves the app-private sandbox, so say so before it happens —
         * once the file is in Downloads any app with storage access can read it.
         */
        private fun confirmExport() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Export data")
                .setMessage(
                    "This saves an unencrypted copy of the database — your chat history and " +
                        "the full record of every action the agent has taken — to a file you " +
                        "choose.\n\n" +
                        "Inside the app it is protected by Android's per-app sandbox. Once " +
                        "exported it is not: anything with access to that folder can read it. " +
                        "Codes, card numbers and credentials are redacted from the action log " +
                        "before it is stored, but app names, timestamps, message subjects and " +
                        "your chat messages are kept as they are.\n\n" +
                        "Stored memories are included, and they are the one exception to " +
                        "redaction: a phone number or address you typed yourself is kept " +
                        "exactly as you wrote it, because that is what made it worth " +
                        "remembering. Review them under Memory before exporting."
                )
                .setPositiveButton("Choose location") { _, _ ->
                    exportLauncher.launch(DataExport.suggestedFileName())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun runExport(uri: Uri) {
            viewLifecycleOwner.lifecycleScope.launch {
                val result = DataExport.exportTo(requireContext().applicationContext, uri)
                val message = result.fold(
                    onSuccess = { "Exported ${DataExport.formatSize(it)}" },
                    onFailure = { "Export failed: ${it.message}" }
                )
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }

        /**
         * Clears both durable agent records in one action: the audit trail of what was
         * *done*, and the triage log of what was *decided*.
         *
         * They are deliberately not two settings entries. Both are "what the agent got
         * up to while I wasn't looking", both end up in the same [DataExport] file, and
         * a user who clears one and is left with the other has deleted their history in
         * name only. Splitting them would be a distinction the app understands and the
         * person pressing the button does not.
         */
        private fun confirmClearActionLog() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear agent history")
                .setMessage(
                    "Permanently deletes both the record of every action the agent has " +
                        "taken and its decisions about your notifications. " +
                        "This cannot be undone — export first if you want a copy."
                )
                .setPositiveButton("Delete") { _, _ ->
                    ActionLogStore.clear()
                    DecisionStore.clear()
                    Toast.makeText(requireContext(), "Agent history cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun buildCapabilitySection() {
            val screen = preferenceScreen
            val category = PreferenceCategory(requireContext()).apply {
                title = "Permissions & Capabilities"
                isIconSpaceReserved = false
            }
            screen.addPreference(category)

            Capability.values().forEach { cap ->
                val pref = Preference(requireContext()).apply {
                    title = cap.label
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener { grant(cap); true }
                }
                category.addPreference(pref)
                capabilityPrefs[cap] = pref
            }
            refreshCapabilityStatuses()
        }

        private fun grant(cap: Capability) {
            // Not a permission and not a Settings page: App info has nothing to toggle.
            if (cap == Capability.PRIVILEGED_SHELL) {
                ShizukuOnboarding.prompt(requireActivity(), cap)
                return
            }
            val runtimePermission = cap.manifestPermission
            if (runtimePermission != null) {
                if (cap.isGranted(requireContext())) return
                pendingCapability = cap
                runtimePermissionLauncher.launch(runtimePermission)
            } else {
                startActivity(specialAccessIntent(cap))
            }
        }

        private fun specialAccessIntent(cap: Capability): Intent = when (cap) {
            Capability.NOTIFICATION_ACCESS ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            Capability.USAGE_ACCESS ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            Capability.OVERLAY ->
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")
                )
            Capability.ALL_FILES_ACCESS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                else Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${requireContext().packageName}")
                )
            else ->
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${requireContext().packageName}")
                )
        }

        private fun refreshCapabilityStatuses() {
            val ctx = context ?: return
            capabilityPrefs.forEach { (cap, pref) ->
                val granted = cap.isGranted(ctx)
                val status = if (granted) "✅ Granted" else "⚠️ Not granted — tap to grant"
                pref.summary = "$status\n${cap.rationale}"
            }
        }

        /**
         * A skill's line reports which of its capabilities are missing, by name, rather
         * than a bare "unavailable" — the missing one is the thing the user can act on,
         * and for `performance` and `context` a gap doesn't stop the skill working, so
         * saying "unavailable" would be wrong as well as unhelpful.
         */
        private fun refreshSkillStatuses() {
            val ctx = context ?: return
            skillPrefs.forEach { (skill, pref) ->
                val missing = skill.capabilities.filterNot { it.isGranted(ctx) }
                val status = if (missing.isEmpty()) {
                    "✅ Ready"
                } else {
                    "⚠️ Limited — needs ${missing.joinToString(", ") { it.label }}"
                }
                val tools = SkillRegistry.toolsOf(skill.id)
                pref.summary = "${skill.summary}\n$status · ${tools.size} tool" +
                    (if (tools.size == 1) "" else "s")
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // The privileged grant lands while this screen is still up (Shizuku's
            // dialog, then the bind), so onResume never sees it; the other capabilities
            // are granted in system Settings and are covered by the resume below.
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    ShellRegistry.state.collect {
                        refreshCapabilityStatuses()
                        refreshSkillStatuses()
                    }
                }
            }
        }

        override fun onResume() {
            refreshSearchStatus()
            super.onResume()
            refreshModelService()
            refreshCalendarSources()
            // The user may have granted a permission in system Settings and returned.
            refreshCapabilityStatuses()
            refreshSkillStatuses()
        }
    }
}
