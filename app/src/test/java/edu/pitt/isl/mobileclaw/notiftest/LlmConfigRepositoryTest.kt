package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.*
import org.junit.Test

class LlmConfigRepositoryTest {
    private class MemoryStorage : LlmConfigStorage {
        var state = LlmStoredState()
        var writeFailure = false
        var resetCount = 0
        override fun loadOrInitialize(developerSeed: () -> LlmProfile?) = state
        override fun write(state: LlmStoredState) {
            if (writeFailure) throw IllegalStateException("unsafe backend diagnostic")
            this.state = state
        }
        override fun resetUnreadableState() { resetCount++ }
    }

    private fun profile(provider: LlmProvider = LlmProvider.OPENAI) =
        LlmProfile.preset(provider).copy(apiKey = "fixture-key").validated()

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
