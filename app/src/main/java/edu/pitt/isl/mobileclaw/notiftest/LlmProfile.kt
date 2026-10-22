package edu.pitt.isl.mobileclaw.notiftest

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

enum class LlmProvider { GEMINI, OPENAI, LOCAL, CUSTOM }
enum class LlmAuth { BEARER, NONE }

/** One provider's configuration. No Android dependencies: validation also runs in JVM tests. */
data class LlmProfile(
    val provider: LlmProvider,
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val auth: LlmAuth = LlmAuth.BEARER,
    val readTimeoutSeconds: Long = 60,
    val inputBudgetTokens: Int = 30_000,
    val supportsImages: Boolean = true
) {
    /** Callers can render an incomplete preset; only validated profiles can be saved. */
    val endpoint: String
        get() = if (baseUrl.isBlank()) "" else normalizeBaseUrl(baseUrl) + "chat/completions"

    val isConfigured: Boolean
        get() = runCatching {
            val normalized = validated()
            normalized.auth == LlmAuth.NONE || normalized.apiKey.isNotBlank()
        }.getOrDefault(false)

    fun validated(): LlmProfile {
        require(model.isNotBlank()) { "Enter a model name." }
        require(model.length <= 256 && model.none { it.isISOControl() }) { "Invalid model name." }
        require(readTimeoutSeconds in 5..900) { "Read timeout must be between 5 and 900 seconds." }
        require(inputBudgetTokens in 1_024..128_000) { "Input budget must be between 1024 and 128000 tokens." }
        require(apiKey.length <= 16_384 && apiKey.all { it.code in 32..126 }) { "Invalid API key." }
        return copy(
            baseUrl = normalizeBaseUrl(baseUrl),
            model = model.trim(),
            apiKey = if (auth == LlmAuth.NONE) "" else apiKey.trim()
        )
    }

    /** Even an invalid, not-yet-validated draft must not reveal credentials in a log. */
    override fun toString(): String =
        "LlmProfile(provider=$provider, baseUrl=<configured>, model=<configured>, " +
            "apiKey=<redacted>, auth=$auth, readTimeoutSeconds=$readTimeoutSeconds, " +
            "inputBudgetTokens=$inputBudgetTokens, supportsImages=$supportsImages)"

    companion object {
        fun preset(provider: LlmProvider): LlmProfile = when (provider) {
            LlmProvider.GEMINI -> LlmProfile(provider,
                "https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-2.5-flash")
            LlmProvider.OPENAI -> LlmProfile(provider, "https://api.openai.com/v1/", "gpt-4o")
            LlmProvider.LOCAL -> LlmProfile(provider, "http://127.0.0.1:8080/v1/", "",
                auth = LlmAuth.NONE, readTimeoutSeconds = 300, inputBudgetTokens = 8_000,
                supportsImages = false)
            LlmProvider.CUSTOM -> LlmProfile(provider, "", "")
        }

        internal fun normalizeBaseUrl(value: String): String {
            val text = value.trim()
            require(text.isNotEmpty() && text.length <= 4_096 &&
                text.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) {
                "Enter a valid base URL."
            }
            // Check the original spelling before OkHttp normalizes alternate IP forms.
            val uri = try { URI(text) } catch (_: Exception) {
                throw IllegalArgumentException("Enter a valid base URL.")
            }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Base URL cannot contain credentials, a query or a fragment."
            }
            require(uri.host != null && uri.port in -1..65_535 && uri.port != 0) {
                "Enter a valid base URL host and port."
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "https" || (scheme == "http" &&
                uri.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2"))) {
                "Use HTTPS, or HTTP on localhost, 127.0.0.1, ::1 or the emulator host 10.0.2.2."
            }
            val url = text.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid base URL.")
            var path = url.encodedPath.trimEnd('/')
            if (path.endsWith("/chat/completions")) path = path.removeSuffix("/chat/completions")
            return url.newBuilder().encodedPath(path.trimEnd('/') + "/").build().toString()
        }
    }
}

data class LlmSnapshot(val profile: LlmProfile, val revision: Long)
