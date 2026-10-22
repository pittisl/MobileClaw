package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Process-wide runtime configuration. Capture one snapshot for each complete agent turn. */
object LlmConfig {
    private const val TAG = "MobileClaw-CFG"

    enum class Status { OK, NOT_CONFIGURED, REJECTED }

    private var repository: LlmConfigRepository? = null
    private val _status = MutableStateFlow(Status.NOT_CONFIGURED)
    val status: StateFlow<Status> = _status

    /** Call once from Application, before services, workers or chat can create clients. */
    @Synchronized
    fun init(context: Context) {
        if (repository != null) return
        val store = EncryptedLlmConfigStore(
            File(context.applicationContext.noBackupFilesDir, "llm-config"),
            "mobileclaw.llm-config.v1"
        )
        repository = LlmConfigRepository(store, ::developerSeed)
        refreshStatus()
    }

    @Synchronized
    fun snapshot(): LlmSnapshot =
        repository?.snapshot() ?: LlmSnapshot(LlmProfile.preset(LlmProvider.CUSTOM), 0)

    @Synchronized
    fun profileFor(provider: LlmProvider): LlmProfile =
        repository?.profileFor(provider) ?: LlmProfile.preset(provider)

    val apiKey: String get() = snapshot().profile.apiKey
    val endpoint: String get() = snapshot().profile.endpoint
    val model: String get() = snapshot().profile.model
    val isConfigured: Boolean get() = snapshot().profile.isConfigured
    val storageError: String? get() = synchronized(this) { repository?.storageError }

    /** Performs synchronous encrypted I/O; UI callers dispatch this to an IO thread. */
    @Synchronized
    fun save(profile: LlmProfile, credentialReentered: Boolean = false) {
        try {
            requireRepository().save(profile, credentialReentered)
        } finally {
            refreshStatus()
        }
    }

    /** Removes only this provider. It never seeds embedded developer credentials again. */
    @Synchronized
    fun clear(provider: LlmProvider) {
        try {
            requireRepository().clear(provider)
        } finally {
            refreshStatus()
        }
    }

    @Synchronized
    fun noteAuthFailure(revision: Long = snapshot().revision) {
        repository?.noteAuthFailure(revision)
        refreshStatus()
    }

    @Synchronized
    fun noteSuccess(revision: Long = snapshot().revision) {
        repository?.noteSuccess(revision)
        refreshStatus()
    }

    private fun requireRepository(): LlmConfigRepository =
        checkNotNull(repository) { "Model settings have not been initialized." }

    private fun refreshStatus() {
        val after = when {
            !isConfigured -> Status.NOT_CONFIGURED
            repository?.isRejected() == true -> Status.REJECTED
            else -> Status.OK
        }
        val before = _status.value
        if (before == after) return
        _status.value = after
        Log.i(TAG, "LLM status: $before -> $after")
        RunStateNotification.refresh()
    }

    /**
     * This is the ONLY reader of BuildConfig.LLM_* fields. Ordinary debug and every
     * release build contain empty values. The opt-in development seed is consumed once
     * per installation, before storage is read; failures never fall back to plaintext.
     */
    private fun developerSeed(): LlmProfile? {
        if (!BuildConfig.DEBUG || !BuildConfig.DEV_LLM_DEFAULTS || BuildConfig.LLM_MODEL.isBlank() ||
            BuildConfig.LLM_ENDPOINT.isBlank()) return null
        val baseUrl = LlmProfile.normalizeBaseUrl(BuildConfig.LLM_ENDPOINT)
        val provider = when (baseUrl) {
            LlmProfile.preset(LlmProvider.GEMINI).baseUrl -> LlmProvider.GEMINI
            LlmProfile.preset(LlmProvider.OPENAI).baseUrl -> LlmProvider.OPENAI
            else -> LlmProvider.CUSTOM
        }
        return LlmProfile(provider, baseUrl, BuildConfig.LLM_MODEL, BuildConfig.LLM_API_KEY)
            .validated()
    }
}
