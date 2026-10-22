package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-owned configuration. Only the explicit connection check makes network requests. */
class ModelServiceActivity : AppCompatActivity() {
    private val providers = LlmProvider.entries.toList()
    private val authMethods = listOf(LlmAuth.BEARER, LlmAuth.NONE)
    private lateinit var provider: Spinner
    private lateinit var auth: Spinner
    private lateinit var baseUrl: EditText
    private lateinit var modelId: EditText
    private lateinit var apiKey: EditText
    private lateinit var timeout: EditText
    private lateinit var inputBudget: EditText
    private lateinit var showKey: CompoundButton
    private lateinit var supportsImages: CompoundButton
    private lateinit var status: TextView
    private lateinit var fieldLayouts: List<TextInputLayout>
    private var selectedProvider = LlmProvider.GEMINI
    private var selectedAuth = LlmAuth.BEARER
    private var populating = false
    private var busy = false
    private var advancedExpanded = false
    private var credentialReentered = false
    private var lastBaseUrl = ""
    private var testJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_service)
        findViewById<TextView>(R.id.headerTitle).setText(R.string.model_service_title)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        provider = findViewById(R.id.modelProvider)
        auth = findViewById(R.id.modelAuth)
        baseUrl = findViewById(R.id.modelBaseUrl)
        modelId = findViewById(R.id.modelId)
        apiKey = findViewById(R.id.modelApiKey)
        timeout = findViewById(R.id.modelTimeout)
        inputBudget = findViewById(R.id.modelInputBudget)
        showKey = findViewById(R.id.modelShowKey)
        supportsImages = findViewById(R.id.modelSupportsImages)
        status = findViewById(R.id.modelServiceStatus)
        fieldLayouts = listOf(R.id.modelBaseUrlLayout, R.id.modelIdLayout,
            R.id.modelApiKeyLayout, R.id.modelTimeoutLayout, R.id.modelInputBudgetLayout)
            .map { findViewById(it) }

        // Never restore a draft URL alongside a key reloaded from encrypted storage.
        // No field in this form participates in Activity view-state persistence.
        findViewById<View>(R.id.modelServiceForm).isSaveFromParentEnabled = false
        apiKey.isSaveFromParentEnabled = false
        apiKey.isSaveEnabled = false

        selectedProvider = savedInstanceState?.getString(STATE_PROVIDER)
            ?.let { saved -> providers.firstOrNull { it.name == saved } }
            ?: LlmConfig.snapshot().profile.provider
        provider.adapter = adapter(providers.map { providerLabel(this, it) })
        auth.adapter = adapter(listOf(getString(R.string.model_service_auth_bearer),
            getString(R.string.model_service_auth_none)))
        provider.setSelection(providers.indexOf(selectedProvider))

        provider.onItemSelectedListener = selectionListener { position ->
            val next = providers[position]
            if (!populating && next != selectedProvider) loadProvider(next)
        }
        auth.onItemSelectedListener = selectionListener { position ->
            val next = authMethods[position]
            if (!populating && next != selectedAuth) {
                selectedAuth = next
                if (next == LlmAuth.NONE) clearDraftKey()
                updateAuthControls()
                markEdited()
            }
        }
        baseUrl.doAfterTextChanged {
            val next = it.toString()
            if (!populating && next != lastBaseUrl) {
                clearDraftKey()
                findViewById<View>(R.id.modelKeyClearedNotice).visibility =
                    if (selectedAuth == LlmAuth.BEARER) View.VISIBLE else View.GONE
                markEdited()
            }
            lastBaseUrl = next
        }
        apiKey.doAfterTextChanged {
            if (!populating) {
                credentialReentered = true
                if (!it.isNullOrBlank()) {
                    findViewById<View>(R.id.modelKeyClearedNotice).visibility = View.GONE
                }
                markEdited()
            }
        }
        listOf(modelId, timeout, inputBudget).forEach { field ->
            field.doAfterTextChanged { if (!populating) markEdited() }
        }
        supportsImages.setOnCheckedChangeListener { _, _ -> if (!populating) markEdited() }
        showKey.setOnCheckedChangeListener { _, checked ->
            val cursor = apiKey.selectionStart
            apiKey.transformationMethod = if (checked) null else PasswordTransformationMethod.getInstance()
            apiKey.setSelection(cursor.coerceIn(0, apiKey.length()))
        }
        findViewById<View>(R.id.modelAdvancedToggle).setOnClickListener {
            showAdvanced(!advancedExpanded)
        }
        findViewById<View>(R.id.modelServiceSave).setOnClickListener { saveProfile() }
        findViewById<View>(R.id.modelServiceTest).setOnClickListener { testConnection() }
        findViewById<View>(R.id.modelServiceClear).setOnClickListener { confirmClear() }
        showAdvanced(savedInstanceState?.getBoolean(STATE_ADVANCED) ?: false)
        loadProvider(selectedProvider)
    }

    private fun adapter(labels: List<String>) = ArrayAdapter(this,
        android.R.layout.simple_spinner_item, labels).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    private fun selectionListener(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            action(position)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun loadProvider(next: LlmProvider) {
        if (busy) return
        selectedProvider = next
        clearDraftKey()
        setBusy(true, R.string.model_service_loading)
        lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { LlmConfig.profileFor(next) }
                populate(profile)
                status.text = if (LlmConfig.storageError != null) {
                    getString(R.string.model_service_load_failed)
                } else ""
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                populate(LlmProfile.preset(next))
                status.setText(R.string.model_service_load_failed)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun populate(profile: LlmProfile) {
        populating = true
        try {
            selectedProvider = profile.provider
            selectedAuth = profile.auth
            provider.setSelection(providers.indexOf(profile.provider))
            auth.setSelection(authMethods.indexOf(profile.auth))
            baseUrl.setText(profile.baseUrl)
            lastBaseUrl = profile.baseUrl
            modelId.setText(profile.model)
            apiKey.setText(if (profile.auth == LlmAuth.BEARER) profile.apiKey else "")
            timeout.setText(profile.readTimeoutSeconds.toString())
            inputBudget.setText(profile.inputBudgetTokens.toString())
            supportsImages.isChecked = profile.supportsImages
            showKey.isChecked = false
            credentialReentered = false
            fieldLayouts.forEach { it.error = null }
            findViewById<View>(R.id.modelKeyClearedNotice).visibility = View.GONE
            findViewById<View>(R.id.modelServiceResults).visibility = View.GONE
        } finally {
            populating = false
        }
        updateAuthControls()
    }

    private fun clearDraftKey() {
        val previous = populating
        populating = true
        apiKey.text.clear()
        showKey.isChecked = false
        credentialReentered = false
        populating = previous
    }

    private fun updateAuthControls() {
        val bearer = selectedAuth == LlmAuth.BEARER
        findViewById<TextInputLayout>(R.id.modelApiKeyLayout).isEnabled = !busy && bearer
        showKey.isEnabled = !busy && bearer
        findViewById<View>(R.id.modelNoAuthHelp).visibility = if (bearer) View.GONE else View.VISIBLE
        if (!bearer) findViewById<View>(R.id.modelKeyClearedNotice).visibility = View.GONE
    }

    private fun markEdited() {
        status.setText(R.string.model_service_edited)
        findViewById<View>(R.id.modelServiceResults).visibility = View.GONE
        fieldLayouts.forEach { it.error = null }
    }

    private fun showAdvanced(expanded: Boolean) {
        advancedExpanded = expanded
        findViewById<View>(R.id.modelAdvancedFields).visibility = if (expanded) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.modelAdvancedToggle).setText(if (expanded)
            R.string.model_service_advanced_hide else R.string.model_service_advanced_show)
    }

    private fun readProfile(requireConfigured: Boolean): LlmProfile? {
        fieldLayouts.forEach { it.error = null }
        var valid = true
        fun error(layout: Int, message: Int) {
            findViewById<TextInputLayout>(layout).error = getString(message)
            valid = false
        }
        if (baseUrl.text.isBlank()) error(R.id.modelBaseUrlLayout, R.string.model_service_url_required)
        else if (runCatching { LlmProfile.normalizeBaseUrl(baseUrl.text.toString()) }.isFailure) {
            error(R.id.modelBaseUrlLayout, R.string.model_service_url_invalid)
        }
        if (modelId.text.isBlank()) error(R.id.modelIdLayout, R.string.model_service_model_required)
        val seconds = timeout.text.toString().toLongOrNull()
        if (seconds == null || seconds !in 5..900) {
            error(R.id.modelTimeoutLayout, R.string.model_service_timeout_invalid)
            showAdvanced(true)
        }
        val budget = inputBudget.text.toString().toIntOrNull()
        if (budget == null || budget !in 1_024..128_000) {
            error(R.id.modelInputBudgetLayout, R.string.model_service_budget_invalid)
            showAdvanced(true)
        }
        if (requireConfigured && selectedAuth == LlmAuth.BEARER && apiKey.text.isBlank()) {
            error(R.id.modelApiKeyLayout, R.string.model_service_key_required)
        }
        if (!valid) return null
        return try {
            LlmProfile(selectedProvider, baseUrl.text.toString(), modelId.text.toString(),
                apiKey.text.toString(), selectedAuth, seconds!!, budget!!, supportsImages.isChecked)
                .validated()
        } catch (_: IllegalArgumentException) {
            status.setText(R.string.model_service_invalid)
            null
        }
    }

    private fun saveProfile() {
        if (busy) return
        val profile = readProfile(requireConfigured = false) ?: return
        val reentered = credentialReentered
        setBusy(true, R.string.model_service_saving)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { LlmConfig.save(profile, credentialReentered = reentered) }
                populate(profile)
                status.setText(if (profile.isConfigured) R.string.model_service_saved
                    else R.string.model_service_saved_without_key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status.setText(R.string.model_service_save_failed)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun confirmClear() {
        if (busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.model_service_clear_title, providerLabel(this, selectedProvider)))
            .setMessage(R.string.model_service_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.model_service_clear) { _, _ -> clearProfile() }
            .show()
    }

    private fun clearProfile() {
        if (busy) return
        val target = selectedProvider
        setBusy(true, R.string.model_service_clearing)
        lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    LlmConfig.clear(target)
                    LlmConfig.profileFor(target)
                }
                populate(profile)
                status.setText(R.string.model_service_cleared)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status.setText(R.string.model_service_clear_failed)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun testConnection() {
        if (busy) return
        val profile = readProfile(requireConfigured = true) ?: return
        findViewById<View>(R.id.modelServiceResults).visibility = View.GONE
        setBusy(true, R.string.model_service_testing)
        testJob = lifecycleScope.launch {
            try {
                val result = ModelConnectionProbe().test(profile)
                renderCheck(R.id.modelResultStreaming, R.string.model_service_check_streaming, result.streaming)
                renderCheck(R.id.modelResultTools, R.string.model_service_check_tools, result.toolCalling)
                renderCheck(R.id.modelResultJson, R.string.model_service_check_json, result.structuredOutput)
                renderCheck(R.id.modelResultVision, R.string.model_service_check_vision, result.vision)
                findViewById<View>(R.id.modelServiceResults).visibility = View.VISIBLE
                status.setText(R.string.model_service_test_finished)
            } catch (e: CancellationException) {
                status.setText(R.string.model_service_test_cancelled)
                throw e
            } catch (_: Exception) {
                status.setText(R.string.model_service_test_failed)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun renderCheck(viewId: Int, title: Int, check: ModelConnectionProbe.Check) {
        val verdict = when (check.status) {
            ModelConnectionProbe.Status.SUPPORTED -> R.string.model_service_check_supported
            ModelConnectionProbe.Status.UNSUPPORTED -> R.string.model_service_check_unsupported
            ModelConnectionProbe.Status.FAILED -> R.string.model_service_check_failed
            ModelConnectionProbe.Status.NOT_CHECKED -> R.string.model_service_check_not_checked
        }
        // Probe details are fixed local messages; no server response or exception is displayed.
        findViewById<TextView>(viewId).text = getString(R.string.model_service_check_result,
            getString(title), getString(verdict), check.detail)
    }

    private fun setBusy(value: Boolean, message: Int? = null) {
        busy = value
        if (message != null) status.setText(message)
        findViewById<View>(R.id.modelServiceProgress).visibility = if (value) View.VISIBLE else View.GONE
        listOf<View>(provider, auth, supportsImages, findViewById(R.id.modelAdvancedToggle),
            findViewById(R.id.modelServiceSave), findViewById(R.id.modelServiceTest),
            findViewById(R.id.modelServiceClear)).forEach { it.isEnabled = !value }
        fieldLayouts.forEach { it.isEnabled = !value }
        updateAuthControls()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Safe UI navigation only. Form fields, including keys, are deliberately absent.
        outState.putString(STATE_PROVIDER, selectedProvider.name)
        outState.putBoolean(STATE_ADVANCED, advancedExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        showKey.isChecked = false
        super.onPause()
    }

    override fun onStop() {
        testJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        clearDraftKey()
        super.onDestroy()
    }

    companion object {
        private const val STATE_PROVIDER = "model_service_provider"
        private const val STATE_ADVANCED = "model_service_advanced"

        internal fun providerLabel(context: Context, provider: LlmProvider): String = context.getString(
            when (provider) {
                LlmProvider.GEMINI -> R.string.model_service_gemini
                LlmProvider.OPENAI -> R.string.model_service_openai
                LlmProvider.LOCAL -> R.string.model_service_local
                LlmProvider.CUSTOM -> R.string.model_service_custom
            })
    }
}
