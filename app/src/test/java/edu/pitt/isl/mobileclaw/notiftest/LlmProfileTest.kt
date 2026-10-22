package edu.pitt.isl.mobileclaw.notiftest

import org.junit.Assert.*
import org.junit.Test

class LlmProfileTest {
    private fun profile(url: String = "https://example.org/v1/") =
        LlmProfile(LlmProvider.CUSTOM, url, " test-model ", " fixture-key ")

    @Test fun normalizesApiRootAndPastedCompletionEndpoint() {
        for (url in listOf("https://EXAMPLE.org:443/v1", "https://example.org/v1/",
            "https://example.org/v1/chat/completions", "https://example.org/v1/chat/completions/")) {
            val normalized = profile(url).validated()
            assertEquals("https://example.org/v1/", normalized.baseUrl)
            assertEquals("https://example.org/v1/chat/completions", normalized.endpoint)
            assertEquals("test-model", normalized.model)
            assertEquals("fixture-key", normalized.apiKey)
            assertEquals(normalized, normalized.validated())
        }
    }

    @Test fun allowsOnlyExactLocalHttpHosts() {
        for (host in listOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2")) {
            assertTrue(profile("http://$host:8080/v1").validated().endpoint.startsWith("http://"))
        }
        for (host in listOf("192.168.1.5", "127.0.0.2", "127.1", "2130706433",
            "localhost.example.org", "127.0.0.1.example.org", "[::ffff:127.0.0.1]", "[0:0:0:0:0:0:0:1]")) {
            invalid { profile("http://$host:8080/v1").validated() }
        }
    }

    @Test fun rejectsCredentialAndRedirectLikeUrlSyntaxWithoutEchoingIt() {
        for (url in listOf("https://user:secret@example.org/v1", "https://example.org/v1?key=secret",
            "https://example.org/v1#secret", "https://example.org:0/v1",
            "ftp://example.org/v1", "https://example.org/white space", "https://example.org/\nsecret",
            "https://example.org\\@attacker.example/v1")) {
            val error = invalid { profile(url).validated() }
            assertFalse(error.message.orEmpty().contains("secret"))
            assertFalse(error.message.orEmpty().contains("attacker"))
        }
    }

    @Test fun noAuthCanBeConfiguredWithoutAKeyAndDropsAnyStoredKey() {
        assertFalse(profile().copy(apiKey = "").isConfigured)
        val anonymous = profile().copy(auth = LlmAuth.NONE).validated()
        assertTrue(anonymous.isConfigured)
        assertEquals("", anonymous.apiKey)
        assertFalse(anonymous.copy(model = "").isConfigured)
        assertFalse(anonymous.copy(baseUrl = "").isConfigured)
    }

    @Test fun validatesBudgetsTimeoutsAndHeaderCharacters() {
        for (timeout in listOf(4L, 901L)) invalid { profile().copy(readTimeoutSeconds = timeout).validated() }
        for (budget in listOf(1_023, 128_001)) invalid { profile().copy(inputBudgetTokens = budget).validated() }
        invalid { profile().copy(apiKey = "key\r\nAuthorization: injected").validated() }
        invalid { profile().copy(model = "model\nother").validated() }
        invalid { profile().copy(apiKey = "secret-中文").validated() }
        assertEquals(5L, profile().copy(readTimeoutSeconds = 5).validated().readTimeoutSeconds)
        assertEquals(128_000, profile().copy(inputBudgetTokens = 128_000).validated().inputBudgetTokens)
    }

    @Test fun presetsAreUnconfiguredAndLocalDefaultsAreConservative() {
        for (provider in LlmProvider.entries) assertFalse(LlmProfile.preset(provider).isConfigured)
        val local = LlmProfile.preset(LlmProvider.LOCAL)
        assertEquals(LlmAuth.NONE, local.auth)
        assertEquals(300L, local.readTimeoutSeconds)
        assertEquals(8_000, local.inputBudgetTokens)
        assertFalse(local.supportsImages)
        assertEquals("gemini-2.5-flash", LlmProfile.preset(LlmProvider.GEMINI).model)
        assertEquals("gpt-4o", LlmProfile.preset(LlmProvider.OPENAI).model)
    }

    @Test fun diagnosticStringsNeverContainDraftSecrets() {
        val draft = profile("https://user:secret@example.org/v1?key=other-secret")
            .copy(apiKey = "third-secret")
        assertFalse(draft.toString().contains("secret"))
        assertFalse(LlmSnapshot(draft, 9).toString().contains("secret"))
        assertTrue(draft.toString().contains("apiKey=<redacted>"))
    }

    private fun invalid(block: () -> Unit): IllegalArgumentException {
        try { block() } catch (error: IllegalArgumentException) { return error }
        fail("Expected invalid configuration to be rejected")
        throw AssertionError()
    }
}
