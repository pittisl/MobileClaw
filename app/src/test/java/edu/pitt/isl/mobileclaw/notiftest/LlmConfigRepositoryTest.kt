package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.*
import org.junit.Test

class LlmConfigRepositoryTest {
    private class MemoryStorage : LlmConfigStorage {
        var state = LlmStoredState()
        var writeFailure = false
        var writeCount = 0
        var initializeFromSeed = false
        var resetCount = 0
        override fun loadOrInitialize(developerSeed: () -> LlmProfile?): LlmStoredState {
            if (initializeFromSeed) {
                initializeFromSeed = false
                developerSeed()?.let { state = LlmStoredState(mapOf(it.provider to it), it.provider) }
            }
            return state
        }
        override fun write(state: LlmStoredState) {
            if (writeFailure) throw IllegalStateException("unsafe backend diagnostic")
            this.state = state
            writeCount++
        }
        override fun resetUnreadableState() { resetCount++ }
    }

    private fun profile(provider: LlmProvider = LlmProvider.OPENAI) =
        LlmProfile.preset(provider).copy(apiKey = "fixture-key").validated()

    @Test fun migrationPreservesSettingsAndSelectionAndIsDurableAcrossRestart() {
        val legacy = profile(LlmProvider.GEMINI).copy(model = "gemini-2.5-flash",
            readTimeoutSeconds = 120, inputBudgetTokens = 12_000, supportsImages = false)
        val other = profile()
        val storage = MemoryStorage().apply {
            state = LlmStoredState(mapOf(LlmProvider.GEMINI to legacy, LlmProvider.OPENAI to other),
                LlmProvider.OPENAI)
        }
        val repository = LlmConfigRepository(storage)
        val upgraded = legacy.copy(model = "gemini-3.5-flash-lite")
        assertEquals(other, repository.snapshot().profile)
        assertEquals(upgraded, repository.profileFor(LlmProvider.GEMINI))
        assertEquals(upgraded, storage.state.profiles[LlmProvider.GEMINI])
        assertEquals(1, storage.writeCount)
        val restarted = LlmConfigRepository(storage)
        assertEquals(upgraded, restarted.profileFor(LlmProvider.GEMINI))
        assertEquals(other, restarted.snapshot().profile)
        assertEquals(1, storage.writeCount)
    }

    @Test fun developerSeedIsMigratedBeforeItBecomesActive() {
        val legacy = profile(LlmProvider.GEMINI).copy(model = "gemini-2.5-flash")
        val storage = MemoryStorage().apply { initializeFromSeed = true }
        val repository = LlmConfigRepository(storage) { legacy }
        assertEquals(legacy.copy(model = "gemini-3.5-flash-lite"), repository.snapshot().profile)
        assertEquals(repository.snapshot().profile, storage.state.profiles[LlmProvider.GEMINI])
        assertEquals(1, storage.writeCount)
    }

    @Test fun migrationUsesExactModelAndHttpsHostInsteadOfProviderLabel() {
        val legacy = profile(LlmProvider.GEMINI).copy(model = "gemini-2.5-flash")
        val untouched = listOf(
            legacy.copy(baseUrl = "https://proxy.example/v1/"),
            legacy.copy(baseUrl = "https://generativelanguage.googleapis.com.example.org/v1/"),
            legacy.copy(baseUrl = "http://generativelanguage.googleapis.com/v1/"),
            legacy.copy(baseUrl = "not a url"),
            legacy.copy(model = "gemini-2.5-flash-lite"),
            legacy.copy(model = "models/gemini-2.5-flash"),
            legacy.copy(model = "gemini-3.5-flash-lite")
        )
        for (original in untouched) {
            val storage = MemoryStorage().apply {
                state = LlmStoredState(mapOf(original.provider to original), original.provider)
            }
            assertEquals(original, LlmConfigRepository(storage).snapshot().profile)
            assertEquals(0, storage.writeCount)
        }
        val custom = legacy.copy(provider = LlmProvider.CUSTOM,
            baseUrl = "HTTPS://GENERATIVELANGUAGE.GOOGLEAPIS.COM/v1beta/openai/")
        val storage = MemoryStorage().apply {
            state = LlmStoredState(mapOf(custom.provider to custom), custom.provider)
        }
        assertEquals(custom.copy(model = "gemini-3.5-flash-lite"),
            LlmConfigRepository(storage).snapshot().profile)
    }

    @Test fun failedMigrationPublishesNoCredentialsAndCanRetryOnRestart() {
        val legacy = profile(LlmProvider.GEMINI).copy(model = "gemini-2.5-flash")
        val storage = MemoryStorage().apply {
            state = LlmStoredState(mapOf(legacy.provider to legacy), legacy.provider)
            writeFailure = true
        }
        val repository = LlmConfigRepository(storage)
        assertFalse(repository.snapshot().profile.isConfigured)
        assertEquals("", repository.snapshot().profile.apiKey)
        assertNotNull(repository.storageError)
        assertFalse(repository.storageError.orEmpty().contains("unsafe"))
        assertEquals(legacy, storage.state.profiles[legacy.provider])
        storage.writeFailure = false
        val restarted = LlmConfigRepository(storage)
        assertEquals(legacy.copy(model = "gemini-3.5-flash-lite"), restarted.snapshot().profile)
        assertNull(restarted.storageError)
        assertEquals(0, storage.resetCount)
    }

    @Test fun providersKeepSeparateProfilesAndSaveSelectsTheProvider() {
        val repository = LlmConfigRepository(MemoryStorage())
        repository.save(profile())
        repository.save(profile(LlmProvider.GEMINI).copy(apiKey = "other-key"))
        assertEquals("fixture-key", repository.profileFor(LlmProvider.OPENAI).apiKey)
        assertEquals("other-key", repository.snapshot().profile.apiKey)
        assertEquals(LlmProvider.GEMINI, repository.snapshot().profile.provider)
    }

    @Test fun changedUrlCannotReuseAKeyWithoutExplicitReentry() {
        val repository = LlmConfigRepository(MemoryStorage())
        repository.save(profile())
        val before = repository.snapshot()
        val changed = before.profile.copy(baseUrl = "https://other.example/v1")
        try {
            repository.save(changed)
            fail("Credential moved to another base URL without reentry")
        } catch (_: IllegalArgumentException) {}
        assertEquals(before, repository.snapshot())
        repository.save(changed, credentialReentered = true)
        assertEquals("https://other.example/v1/", repository.snapshot().profile.baseUrl)
    }

    @Test fun equivalentUrlSpellingDoesNotRequireReentry() {
        val repository = LlmConfigRepository(MemoryStorage())
        repository.save(profile())
        repository.save(profile().copy(baseUrl = "https://API.OPENAI.COM:443/v1/chat/completions/"))
        assertTrue(repository.snapshot().profile.isConfigured)
    }

    @Test fun newKeyAndNoAuthCanMoveToANewEndpoint() {
        val repository = LlmConfigRepository(MemoryStorage())
        repository.save(profile())
        repository.save(profile().copy(baseUrl = "https://other.example/v1", apiKey = "new-key"))
        repository.save(profile().copy(baseUrl = "https://third.example/v1", auth = LlmAuth.NONE))
        assertEquals("", repository.snapshot().profile.apiKey)
        assertTrue(repository.snapshot().profile.isConfigured)
    }

    @Test fun lateAuthResultsDoNotChangeTheNewRevision() {
        val repository = LlmConfigRepository(MemoryStorage())
        repository.save(profile())
        val first = repository.snapshot()
        repository.save(profile().copy(apiKey = "replacement-key"))
        val second = repository.snapshot()
        assertTrue(second.revision > first.revision)
        repository.noteAuthFailure(first.revision)
        assertFalse(repository.isRejected())
        repository.noteAuthFailure(second.revision)
        assertTrue(repository.isRejected())
        repository.noteSuccess(first.revision)
        assertTrue(repository.isRejected())
        repository.noteSuccess(second.revision)
        assertFalse(repository.isRejected())
    }

    @Test fun clearNeverSelectsAnotherProvidersCredential() {
        val repository = LlmConfigRepository(MemoryStorage())
        repository.save(profile(LlmProvider.GEMINI))
        repository.save(profile())
        repository.clear(LlmProvider.OPENAI)
        assertEquals(LlmProvider.OPENAI, repository.snapshot().profile.provider)
        assertFalse(repository.snapshot().profile.isConfigured)
        assertEquals("fixture-key", repository.profileFor(LlmProvider.GEMINI).apiKey)
    }

    @Test fun failedWriteFailsClosedAndUserInputCanRecover() {
        val storage = MemoryStorage()
        val repository = LlmConfigRepository(storage)
        repository.save(profile())
        val before = repository.snapshot().revision
        storage.writeFailure = true
        try {
            repository.save(profile().copy(apiKey = "replacement"))
            fail("Write failure was hidden")
        } catch (error: IllegalStateException) {
            assertFalse(error.message.orEmpty().contains("unsafe"))
        }
        assertFalse(repository.snapshot().profile.isConfigured)
        assertTrue(repository.snapshot().revision > before)
        assertNotNull(repository.storageError)
        storage.writeFailure = false
        repository.clear(LlmProvider.OPENAI)
        repository.save(profile().copy(apiKey = "reentered"))
        assertEquals(1, storage.resetCount)
        assertNull(repository.storageError)
        assertEquals("reentered", repository.snapshot().profile.apiKey)
    }
}
