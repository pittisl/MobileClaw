package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context

/** All parts of a run use one immutable configuration, including its model and auth mode. */
object LlmClients {
    fun chat(snapshot: LlmSnapshot, contextProvider: (() -> String)? = null): AiClient =
        snapshot.profile.let { p ->
            OpenAiAiClient(p.apiKey, p.endpoint, contextProvider,
                budgetTokens = p.inputBudgetTokens,
                toolBudgetTokens = minOf(ToolOutputBudget.DEFAULT_BUDGET_TOKENS, p.inputBudgetTokens / 4),
                readTimeoutSeconds = p.readTimeoutSeconds, auth = p.auth,
                enforceInputBudget = true)
        }

    fun reasoning(snapshot: LlmSnapshot): ReasoningModule = snapshot.profile.let { p ->
        ReasoningModule(p.apiKey, p.endpoint, p.model, readTimeoutSeconds = p.readTimeoutSeconds,
            auth = p.auth, supportsImages = p.supportsImages, configRevision = snapshot.revision,
            inputBudgetTokens = p.inputBudgetTokens)
    }

    fun dailyPass(context: Context, snapshot: LlmSnapshot = LlmConfig.snapshot()): DailyPassRunner =
        snapshot.profile.let { p ->
            DailyPassRunner(context, p.endpoint, p.apiKey, p.model, p.auth,
                p.readTimeoutSeconds, snapshot.revision, p.inputBudgetTokens)
        }
}

internal fun String.withoutCredential(key: String): String =
    if (key.isBlank()) this else replace(key, "[redacted]")
