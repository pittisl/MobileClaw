package edu.pitt.isl.mobileclaw.notiftest

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TokenAudit]'s arithmetic — the instrument the budgets' follow-up is
 * going to be read off.
 *
 * These pin the *instrument*, not the finding. Whether `chars / 4` is actually wrong is a
 * question for real `usage` figures on a real request; what these guarantee is that when
 * it is wrong, the reading says so and says where — a broken audit that quietly reported
 * `ratio=1.00` would end the investigation with the wrong answer rather than no answer.
 */
class TokenAuditTest {

    private fun msg(role: String, text: String) = TokenAudit.Message(role, text)

    @Test
    fun eachRoleLandsInTheBucketThatCanBeChanged() {
        val reading = TokenAudit.measure(
            messages = listOf(
                msg("system", "a".repeat(400)),
                msg("user", "b".repeat(200)),
                msg("assistant", "c".repeat(200)),
                msg("tool", "d".repeat(800))
            ),
            catalogJson = "e".repeat(4_000),
            reportedPromptTokens = 0
        )

        assertEquals(1_000, reading.catalog)
        assertEquals(100, reading.system)
        // user + assistant together: they are what HistoryBudget selects between.
        assertEquals(100, reading.history)
        assertEquals(200, reading.toolOutput)
        assertEquals(4, reading.messages)
    }

    /** The total is the sum of the parts, or the breakdown is describing a different request. */
    @Test
    fun theEstimateIsExactlyItsParts() {
        val reading = TokenAudit.measure(
            messages = listOf(msg("system", "x".repeat(120)), msg("user", "y".repeat(80))),
            catalogJson = "z".repeat(40),
            reportedPromptTokens = 999
        )

        assertEquals(
            reading.catalog + reading.system + reading.history + reading.toolOutput,
            reading.estimated
        )
        assertEquals(999, reading.reported)
    }

    /**
     * The reading that would mean the budgets are not ceilings: the provider billed more
     * than we predicted. Above 1.0 is the dangerous direction, and the number has to
     * survive to the log line rather than being rounded into invisibility.
     */
    @Test
    fun aProviderBillingMoreThanPredictedShowsAsARatioAboveOne() {
        val reading = TokenAudit.measure(
            messages = listOf(msg("user", "w".repeat(400))),  // 100 estimated
            catalogJson = null,
            reportedPromptTokens = 350
        )

        assertEquals(100, reading.estimated)
        assertTrue("ratio must exceed 1 when billed above the estimate", reading.ratio > 1.0)
        assertTrue(reading.format().contains("ratio=3.50"))
    }

    /**
     * The attribution half. `chars / 4` assumes about four characters per token, which is
     * an English-prose fact; CJK text runs far closer to one character per token, so the
     * estimate can be low by a multiple. [TokenAudit.Reading.nonAsciiPercent] is what lets
     * a bad ratio be pinned on that rather than left as a mystery — this is the case that
     * would make the difference readable in a log full of mixed traffic.
     */
    @Test
    fun nonAsciiTextIsReportedSoABadRatioCanBeAttributed() {
        val english = TokenAudit.measure(
            messages = listOf(msg("user", "the quick brown fox jumps over the lazy dog")),
            catalogJson = null,
            reportedPromptTokens = 10
        )
        assertEquals(0, english.nonAsciiPercent)

        val chinese = TokenAudit.measure(
            messages = listOf(msg("user", "请把今天的通知总结一下并告诉我哪些需要马上处理")),
            catalogJson = null,
            reportedPromptTokens = 10
        )
        assertTrue("Chinese text must register as non-ASCII", chinese.nonAsciiPercent > 90)

        // And the mixed case, which is what this app's traffic actually looks like.
        val mixed = TokenAudit.measure(
            messages = listOf(msg("user", "summarize 今天的通知")),
            catalogJson = null,
            reportedPromptTokens = 10
        )
        assertTrue(mixed.nonAsciiPercent in 1..99)
    }

    /**
     * A request whose usage never arrived must not read as a perfect prediction. Providers
     * that reject `stream_options` report nothing, and `0/0 = ` a ratio of 1.00 would put
     * a row in the sample claiming the estimator was exactly right.
     */
    @Test
    fun aRequestWithNoReportedUsageDoesNotLookLikeAgreement() {
        val reading = TokenAudit.measure(
            messages = listOf(msg("user", "hello")),
            catalogJson = null,
            reportedPromptTokens = 0
        )

        assertEquals(0.0, reading.ratio, 0.0001)
    }

    /**
     * The trap [TokenAudit.from] exists for. An assistant turn that requested tools carries
     * its payload in `tool_calls` and has **no `content` field at all**; measuring content
     * would price the tool loop's echoes — re-sent on every iteration, the thing
     * `ToolOutputBudget` was written to bound — at zero, and the audit would report the
     * loop getting cheaper the more it looped.
     */
    @Test
    fun anAssistantTurnWithToolCallsAndNoContentIsNotFree() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "s".repeat(40)))
            .put(
                JSONObject().put("role", "assistant").put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "call_1")
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", "read_notifications")
                                    .put("arguments", "{\"limit\":25}")
                            )
                    )
                )
            )
            .put(JSONObject().put("role", "tool").put("content", "t".repeat(200)))

        val parts = TokenAudit.from(messages)
        assertEquals(listOf("system", "assistant", "tool"), parts.map { it.role })

        val reading = TokenAudit.measure(parts, catalogJson = null, reportedPromptTokens = 0)
        assertTrue(
            "the tool_calls echo must cost something",
            reading.history > 0
        )
        assertTrue(reading.toolOutput >= 50)
    }

    /** An empty request divides by nothing. */
    @Test
    fun anEmptyRequestIsNotADivideByZero() {
        val reading = TokenAudit.measure(emptyList(), null, 0)

        assertEquals(0, reading.estimated)
        assertEquals(0.0, reading.ratio, 0.0001)
        assertEquals(0, reading.nonAsciiPercent)
    }
}
