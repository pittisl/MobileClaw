package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Uses real Keystore/AtomicFile with a fresh test-only directory and alias per case. */
@RunWith(AndroidJUnit4::class)
class EncryptedLlmConfigStoreTest {
    private lateinit var directory: File
    private lateinit var alias: String

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        directory = File(context.noBackupFilesDir, "llm-config-test-" + id)
        alias = "mobileclaw.test.llm." + id
    }

    @After fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        directory.deleteRecursively()
    }

    private fun store() = EncryptedLlmConfigStore(directory, alias)
    private fun profile(provider: LlmProvider = LlmProvider.OPENAI) =
        LlmProfile.preset(provider).copy(apiKey = "test-only-secret-8f51b").validated()

    @Test fun encryptedProfilesAndActiveProviderSurviveReopeningStorage() {
        val first = LlmConfigRepository(store())
        first.save(profile())
        first.save(profile(LlmProvider.GEMINI).copy(apiKey = "test-only-second-key"))
        val second = LlmConfigRepository(store())
        assertNull(second.storageError)
        assertEquals(LlmProvider.GEMINI, second.snapshot().profile.provider)
        assertEquals("test-only-second-key", second.snapshot().profile.apiKey)
        assertEquals(profile(), second.profileFor(LlmProvider.OPENAI))
        val bytes = File(directory, "profiles.enc").readBytes()
        val raw = String(bytes, Charsets.ISO_8859_1)
        assertFalse(raw.contains("test-only-secret-8f51b"))
        assertFalse(raw.contains("test-only-second-key"))
        assertFalse(raw.contains("generativelanguage.googleapis.com"))
    }

    @Test fun everyWriteUsesANewGcmIv() {
        val store = store()
        val state = LlmStoredState(mapOf(LlmProvider.OPENAI to profile()), LlmProvider.OPENAI)
        store.write(state)
        val first = store.encryptedFile.readBytes()
        store.write(state)
        val second = store.encryptedFile.readBytes()
        // Format and IV length occupy 8 bytes; the following 12 bytes are the GCM IV.
        assertFalse(first.copyOfRange(8, 20).contentEquals(second.copyOfRange(8, 20)))
        assertEquals(profile(), store().loadOrInitialize { null }.profiles[LlmProvider.OPENAI])
    }

    @Test fun developerSeedIsConsumedOnceAndClearSurvivesReopening() {
        var seeds = 0
        val first = LlmConfigRepository(store()) { seeds++; profile() }
        assertEquals(1, seeds)
        assertTrue(first.snapshot().profile.isConfigured)
        first.clear(LlmProvider.OPENAI)
        val second = LlmConfigRepository(store()) { seeds++; profile() }
        assertEquals(1, seeds)
        assertFalse(second.snapshot().profile.isConfigured)
        assertEquals("", second.profileFor(LlmProvider.OPENAI).apiKey)
        assertNull(second.storageError)
    }

    @Test fun tamperingFailsClosedWithoutReseedingAndClearAllowsNewInput() {
        val first = LlmConfigRepository(store()) { profile() }
        assertTrue(first.snapshot().profile.isConfigured)
        val encrypted = File(directory, "profiles.enc")
        val bytes = encrypted.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        encrypted.writeBytes(bytes)
        var seeds = 0
        val broken = LlmConfigRepository(store()) { seeds++; profile() }
        assertEquals(0, seeds)
        assertFalse(broken.snapshot().profile.isConfigured)
        assertEquals("", broken.profileFor(LlmProvider.OPENAI).apiKey)
        assertNotNull(broken.storageError)
        broken.clear(LlmProvider.OPENAI)
        broken.save(profile().copy(apiKey = "new-test-only-key"))
        val restored = LlmConfigRepository(store()) { seeds++; profile() }
        assertEquals(0, seeds)
        assertNull(restored.storageError)
        assertEquals("new-test-only-key", restored.snapshot().profile.apiKey)
    }

    @Test fun aMissingKeystoreKeyFailsClosedAndExplicitSaveCanRecover() {
        LlmConfigRepository(store()).save(profile())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        var seeds = 0
        val broken = LlmConfigRepository(store()) { seeds++; profile() }
        assertFalse(broken.snapshot().profile.isConfigured)
        assertNotNull(broken.storageError)
        assertEquals(0, seeds)
        val local = LlmProfile.preset(LlmProvider.LOCAL).copy(model = "test-local").validated()
        broken.save(local)
        assertEquals(local, LlmConfigRepository(store()).snapshot().profile)
    }

    @Test fun missingCiphertextDoesNotResurrectAnEmbeddedDeveloperKey() {
        LlmConfigRepository(store()) { profile() }
        assertTrue(File(directory, "profiles.enc").delete())
        var seeds = 0
        val reopened = LlmConfigRepository(store()) { seeds++; profile() }
        assertEquals(0, seeds)
        assertFalse(reopened.snapshot().profile.isConfigured)
    }

    @Test fun anInitializationFailureConsumesTheSeedOpportunityBeforeRetry() {
        var seedAttempts = 0
        val broken = LlmConfigRepository(store()) {
            seedAttempts++
            throw IllegalStateException("Synthetic initialization failure")
        }
        assertEquals(1, seedAttempts)
        assertNotNull(broken.storageError)
        val reopened = LlmConfigRepository(store()) { seedAttempts++; profile() }
        assertEquals(1, seedAttempts)
        assertFalse(reopened.snapshot().profile.isConfigured)
    }
}
