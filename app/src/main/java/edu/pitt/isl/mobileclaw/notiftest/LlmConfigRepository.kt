package edu.pitt.isl.mobileclaw.notiftest

import java.net.URI

internal interface LlmConfigStorage {
    fun loadOrInitialize(developerSeed: () -> LlmProfile?): LlmStoredState
    fun write(state: LlmStoredState)
    fun resetUnreadableState()
}

/** Transactional in-memory view: publish a new revision only after durable storage. */
internal class LlmConfigRepository(
    private val storage: LlmConfigStorage,
    developerSeed: () -> LlmProfile? = { null }
) {
    private var state = LlmStoredState()
    private var revision = 0L
    private var rejected = false
    private var needsRecovery = false
    var storageError: String? = null
        private set

    init {
        try {
            val loaded = storage.loadOrInitialize(developerSeed)
            val migrated = loaded.copy(profiles = loaded.profiles.mapValues { (_, profile) ->
                val uri = runCatching { URI(profile.baseUrl) }.getOrNull()
                if (profile.model == "gemini-2.5-flash" &&
                    uri?.scheme.equals("https", ignoreCase = true) &&
                    uri?.host.equals("generativelanguage.googleapis.com", ignoreCase = true)) {
                    profile.copy(model = "gemini-3.5-flash-lite")
                } else profile
            })
            // Upgrade inactive profiles and development seeds too. Publish only after
            // the encrypted store has accepted the migration; restart then is a no-op.
            if (migrated != loaded) storage.write(migrated)
            state = migrated
            revision++
        } catch (_: Exception) {
            failClosed()
        }
    }

    @Synchronized
    fun snapshot(): LlmSnapshot = LlmSnapshot(profileFor(state.active), revision)

    @Synchronized
    fun profileFor(provider: LlmProvider): LlmProfile =
        state.profiles[provider] ?: LlmProfile.preset(provider)

    @Synchronized
    fun save(profile: LlmProfile, credentialReentered: Boolean = false) {
        val normalized = profile.validated()
        val old = state.profiles[normalized.provider]
        require(old == null || old.baseUrl == normalized.baseUrl || normalized.apiKey.isEmpty() ||
            normalized.apiKey != old.apiKey || credentialReentered) {
            "Re-enter the API key after changing the base URL."
        }
        persist(LlmStoredState(state.profiles + (normalized.provider to normalized), normalized.provider))
    }

    @Synchronized
    fun clear(provider: LlmProvider) {
        // Keep this provider selected if it was active. Clearing a key must not switch
        // silently to another saved provider or resurrect a developer credential.
        persist(state.copy(profiles = state.profiles - provider, seeded = true))
    }

    @Synchronized
    fun noteAuthFailure(expectedRevision: Long) {
        if (expectedRevision == revision) rejected = true
    }

    @Synchronized
    fun noteSuccess(expectedRevision: Long) {
        if (expectedRevision == revision) rejected = false
    }

    @Synchronized
    fun isRejected(): Boolean = rejected

    private fun persist(next: LlmStoredState) {
        try {
            if (needsRecovery) storage.resetUnreadableState()
            storage.write(next)
            state = next
            revision++
            rejected = false
            storageError = null
            needsRecovery = false
        } catch (_: Exception) {
            failClosed()
            throw IllegalStateException(STORAGE_ERROR)
        }
    }

    private fun failClosed() {
        state = LlmStoredState()
        revision++
        rejected = false
        needsRecovery = true
        storageError = STORAGE_ERROR
    }

    companion object {
        private const val STORAGE_ERROR =
            "Model settings could not be read or saved securely. Re-enter the settings, or clear them and try again."
    }
}
