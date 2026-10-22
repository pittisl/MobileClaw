package edu.pitt.isl.mobileclaw.notiftest.demo

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * One opt-in model request, with no agent loop and no tool execution. Saves the
 * actual request JSON and raw response so an empty completion can be diagnosed
 * independently of SSE parsing. Credentials and HTTP headers are never written.
 */
@RunWith(AndroidJUnit4::class)
class PhotoModelDiagnostic {
    @Test fun captureFirstPhotoRequestWithoutExecutingTools() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e photoDiagnostic true", args.getString("photoDiagnostic") == "true")
        val runId = requireNotNull(args.getString("photoDiagRunId"))
        require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,63}")))
        val variant = args.getString("photoDiagVariant") ?: "full"
        require(variant in setOf("full", "search-only", "without-send-photo", "native"))
        val toolChoice = args.getString("photoDiagToolChoice")
        require(toolChoice == null || toolChoice in setOf("auto", "required"))
        require(toolChoice == null || variant != "native") { "photoDiagToolChoice applies only to OpenAI variants." }
        val reasoningEffort = args.getString("photoDiagReasoningEffort")
        require(reasoningEffort == null || reasoningEffort in setOf("none", "low", "medium"))
        require(reasoningEffort == null || variant != "native") {
            "photoDiagReasoningEffort applies only to OpenAI variants."
        }
        val shortRequestArg = args.getString("photoDiagShortRequest")
        require(shortRequestArg == null || shortRequestArg in setOf("true", "false"))
        val shortRequest = shortRequestArg == "true"
        val thinkingBudget = args.getString("photoDiagThinkingBudget")?.let {
            requireNotNull(it.toIntOrNull()) { "photoDiagThinkingBudget must be an integer." }
        }
        require(thinkingBudget == null || thinkingBudget in 0..8192) {
            "photoDiagThinkingBudget must be between 0 and 8192."
        }
        require(thinkingBudget == null || variant == "native") { "photoDiagThinkingBudget applies only to native." }
        val temperatureOverride = args.getString("photoDiagTemperature")?.let {
            requireNotNull(it.toDoubleOrNull()) { "photoDiagTemperature must be numeric." }
        }
        require(temperatureOverride == null || (temperatureOverride.isFinite() && temperatureOverride in 0.0..2.0)) {
            "photoDiagTemperature must be between 0 and 2."
        }
        val maxTokens = args.getString("photoDiagMaxTokens")?.let {
            requireNotNull(it.toIntOrNull()) { "photoDiagMaxTokens must be an integer." }
        }
        require(maxTokens == null || maxTokens in 1..8192) { "photoDiagMaxTokens must be between 1 and 8192." }
        val padChars = args.getString("photoDiagPadChars")?.let {
            requireNotNull(it.toIntOrNull()) { "photoDiagPadChars must be an integer." }
        } ?: 0
        require(padChars in 0..20_000) { "photoDiagPadChars must be between 0 and 20000." }
        val compactArg = args.getString("photoDiagCompactDescriptions")
        require(compactArg == null || compactArg in setOf("true", "false"))
        val compactDescriptions = compactArg == "true"
        val inSystemArg = args.getString("photoDiagDescriptionsInSystem")
        require(inSystemArg == null || inSystemArg in setOf("true", "false"))
        val descriptionsInSystem = inSystemArg == "true"
        val inFunctionArg = args.getString("photoDiagParameterDescriptionsInFunction")
        require(inFunctionArg == null || inFunctionArg in setOf("true", "false"))
        val parameterDescriptionsInFunction = inFunctionArg == "true"
        val conciseReferenceArg = args.getString("photoDiagConciseParameterReference")
        require(conciseReferenceArg == null || conciseReferenceArg in setOf("true", "false"))
        val conciseParameterReference = conciseReferenceArg == "true"
        require(!conciseParameterReference || parameterDescriptionsInFunction) {
            "Concise parameter references require photoDiagParameterDescriptionsInFunction=true."
        }
        val compactParameterArg = args.getString("photoDiagCompactParameterDescriptions")
        require(compactParameterArg == null || compactParameterArg in setOf("true", "false"))
        val compactParameterDescriptions = compactParameterArg == "true"
        require(!(parameterDescriptionsInFunction && compactParameterDescriptions)) {
            "Choose either parameter-description relocation or the parameter-only removal control."
        }
        require(!(parameterDescriptionsInFunction || compactParameterDescriptions) ||
            (!compactDescriptions && !descriptionsInSystem)) {
            "Parameter-only diagnostics cannot be combined with whole-catalog description transformations."
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.filesDir, "demo-photo-diagnostic/$runId").apply { mkdirs() }
        val key = LlmConfig.apiKey
        val report = JSONObject().put("runId", runId).put("variant", variant)
            .put("liveLlm", true).put("noToolsExecuted", true).put("noApprovalPerformed", true)
            .put("maxTokens", maxTokens ?: JSONObject.NULL).put("paddingCharacters", padChars)
            .put("toolChoice", toolChoice ?: JSONObject.NULL).put("thinkingBudget", thinkingBudget ?: JSONObject.NULL)
            .put("reasoningEffort", reasoningEffort ?: JSONObject.NULL).put("shortRequest", shortRequest)
            .put("temperatureOverride", temperatureOverride ?: JSONObject.NULL)
            .put("compactDescriptions", compactDescriptions)
            .put("descriptionsInSystem", descriptionsInSystem)
            .put("parameterDescriptionsInFunction", parameterDescriptionsInFunction)
            .put("conciseParameterReference", conciseParameterReference)
            .put("compactParameterDescriptions", compactParameterDescriptions)
        val started = SystemClock.elapsedRealtime()
        fun safe(text: String) = if (key.isBlank()) text else text.replace(key, "[configured key removed]")
        try {
            AppSettings.init(context)
            assertTrue("Use the already-paused demo phone", AgentRunState.isPaused)
            assertFalse("Full Automation must be off", AppSettings.fullAutomation)
            assertTrue("No configured model credential", key.isNotBlank())
            SkillRegistry.init(context)
            val spec = AgentRegistry.generalChat
            val temperature = temperatureOverride ?: spec.temperature
            val all = ToolRegistry.all()
            assertTrue("Production search_files schema missing", all.any { it.name == "search_files" })
            assertTrue("Production send_photo schema missing", all.any { it.name == "send_photo" })
            val excluded = args.getString("photoDiagExcludeTools").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
            require(excluded.all { name -> all.any { it.name == name } })
            val selected = (when (variant) {
                "search-only" -> all.filter { it.name == "search_files" }
                "without-send-photo" -> all.filterNot { it.name == "send_photo" }
                else -> all
            }).filterNot { it.name in excluded }
            val photoContext = PhotoTurn("diagnostic-$runId", selectedPhoto = null).modelContext()
            val now = ZonedDateTime.now().withNano(0).format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
            val paddingUnit = "\nDiagnostic padding text. Neutral repeated words.\n"
            val padding = paddingUnit.repeat((padChars + paddingUnit.length - 1) / paddingUnit.length).take(padChars)
            val baseSystem = spec.systemPrompt + "\n\n" + photoContext + "\n\n" +
                "Current device time: $now\n\nNo notifications are currently captured." + padding
            val user = if (shortRequest) "Find my dog photos from yesterday."
                else "find me my photo about the dog yesterday"
            val native = variant == "native"
            val configuredEndpoint = LlmConfig.endpoint.toHttpUrl()
            val endpoint = if (native) {
                require(configuredEndpoint.host == "generativelanguage.googleapis.com" &&
                    configuredEndpoint.scheme == "https" && configuredEndpoint.port == 443) {
                    "Native diagnostic is restricted to the configured Google Gemini host."
                }
                val model = spec.model.removePrefix("models/")
                require(model.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Unexpected Gemini model identifier." }
                configuredEndpoint.newBuilder().username("").password("")
                    .encodedPath("/v1beta/models/$model:streamGenerateContent")
                    .query(null).fragment(null).addQueryParameter("alt", "sse").build()
            } else configuredEndpoint
            val catalog = JSONArray(selected.map { tool ->
                JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", tool.name).put("description", tool.description)
                    .put("parameters", JSONObject(tool.parameters.toString())))
            })
            val declarations = JSONArray(selected.map { tool -> JSONObject()
                .put("name", tool.name).put("description", tool.description)
                .put("parametersJsonSchema", JSONObject(tool.parameters.toString())) })
            val parameterReference = JSONArray()
            var parameterDescriptionsRemoved = 0
            if (parameterDescriptionsInFunction || compactParameterDescriptions) {
                for (index in selected.indices) {
                    val function = if (native) declarations.getJSONObject(index)
                        else catalog.getJSONObject(index).getJSONObject("function")
                    val schemaKey = if (native) "parametersJsonSchema" else "parameters"
                    val schema = function.getJSONObject(schemaKey)
                    val originalDescription = function.getString("description")
                    val entries = JSONArray()
                    collectDescriptions(schema, selected[index].name, "/$schemaKey", entries)
                    val removed = removeDescriptions(schema)
                    assertEquals("Every removed parameter annotation must have its original value recorded", removed, entries.length())
                    parameterDescriptionsRemoved += removed
                    for (entry in 0 until entries.length()) parameterReference.put(entries.getJSONObject(entry))
                    if (parameterDescriptionsInFunction && entries.length() > 0) {
                        function.put("description", originalDescription + "\n\nParameter descriptions by schema path:\n" +
                            (0 until entries.length()).joinToString("\n") {
                                val entry = entries.getJSONObject(it)
                                if (conciseParameterReference)
                                    entry.getString("schemaPath") + ": " + entry.getString("description")
                                else entry.toString()
                            })
                    }
                }
            }
            val reference = JSONArray()
            if (descriptionsInSystem) {
                for (index in selected.indices) {
                    val function = if (native) declarations.getJSONObject(index)
                        else catalog.getJSONObject(index).getJSONObject("function")
                    collectDescriptions(function, selected[index].name, "", reference)
                }
            }
            val referenceText = if (descriptionsInSystem) {
                "\n\nTool reference: original tool and parameter descriptions, indexed by tool name and schema path. " +
                    "These descriptions accompany the unchanged callable schemas.\n" +
                    (0 until reference.length()).joinToString("\n") { reference.getJSONObject(it).toString() }
            } else ""
            val system = baseSystem + referenceText
            // OpenAI variants match AiClient.buildBody's normal first request. The
            // native variant keeps the same prompt, sentence, and complete schemas,
            // but exposes Gemini's original finishReason rather than its adapter's.
            // Default requests have no tool_choice or thinking override. Explicit
            // diagnostics can vary these fields, but never execute returned tools.
            var descriptionsRemoved = parameterDescriptionsRemoved
            val requestBody = if (native) {
                if (compactDescriptions || descriptionsInSystem) descriptionsRemoved = removeDescriptions(declarations)
                val generation = JSONObject().put("temperature", temperature)
                maxTokens?.let { generation.put("maxOutputTokens", it) }
                thinkingBudget?.let { generation.put("thinkingConfig", JSONObject().put("thinkingBudget", it)) }
                JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))))
                    .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
                    .put("generationConfig", generation)
            } else {
                if (compactDescriptions || descriptionsInSystem) descriptionsRemoved = removeDescriptions(catalog)
                JSONObject().put("model", spec.model)
                    .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                    .put("temperature", temperature).put("stream", true).put("tools", catalog)
                    .put("stream_options", JSONObject().put("include_usage", true))
                    .also { body ->
                        maxTokens?.let { body.put("max_tokens", it) }
                        toolChoice?.let { body.put("tool_choice", it) }
                        reasoningEffort?.let { body.put("reasoning_effort", it) }
                    }
            }
            val requestJson = requestBody.toString()
            if (descriptionsInSystem) assertEquals("Every removed description must be retained in SYSTEM",
                descriptionsRemoved, reference.length())
            assertFalse("Credential unexpectedly present in request JSON", requestJson.contains(key))
            output.resolve("request.json").writeText(requestJson + "\n")
            if (descriptionsInSystem) output.resolve("tool-reference.json").writeText(reference.toString(2) + "\n")
            if (parameterDescriptionsInFunction || compactParameterDescriptions)
                output.resolve("parameter-reference.json").writeText(parameterReference.toString(2) + "\n")
            report.put("model", spec.model).put("currentDeviceTime", now)
                .put("userRequest", user)
                .put("temperature", temperature)
                .put("protocol", if (native) "gemini-native" else "openai-compatible")
                .put("toolNames", JSONArray(selected.map { it.name })).put("toolCount", selected.size)
                .put("requestCharacters", requestJson.length).put("systemCharacters", system.length)
                .put("descriptionsRemoved", descriptionsRemoved).put("referenceDescriptionCount", reference.length())
                .put("toolReferenceCharacters", referenceText.length)
                .put("parameterDescriptionCount", parameterReference.length())
            output.resolve("report.json").writeText(report.toString(2) + "\n")
            val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS).build()
            val requestBuilder = Request.Builder().url(endpoint).header("Accept", "text/event-stream")
            if (native) requestBuilder.header("x-goog-api-key", key)
            else requestBuilder.header("Authorization", "Bearer $key")
            val request = requestBuilder
                .post(requestJson.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                // Normally byte-for-byte UTF-8 response text. If a provider ever
                // echoes the credential in its body, remove that literal before disk.
                val persisted = safe(raw)
                output.resolve("response.txt").writeText(persisted)
                report.put("httpStatus", response.code).put("httpSuccessful", response.isSuccessful)
                    .put("responseCharacters", raw.length).put("responseCredentialRedacted", raw != persisted)
                    .put("status", "captured")
                val payloads = JSONArray()
                persisted.lineSequence().filter { it.startsWith("data:") }.forEach { line ->
                    val payload = line.removePrefix("data:").trim()
                    if (payload != "[DONE]") runCatching { JSONObject(payload) }.getOrNull()?.let { payloads.put(it) }
                }
                if (payloads.length() == 0) runCatching { JSONObject(persisted) }.getOrNull()?.let { payloads.put(it) }
                output.resolve("response-payloads.json").writeText(payloads.toString(2) + "\n")
                report.put("jsonPayloadCount", payloads.length())
            }
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", safe("${error.javaClass.simpleName}: ${error.message}"))
            // Avoid printing exception messages that an HTTP library might enrich
            // with a configured URL; the sanitized diagnostic is in report.json.
            throw AssertionError("Photo diagnostic failed; inspect app-private report.json (${error.javaClass.simpleName}).")
        } finally {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("assistantRemainedPaused", AgentRunState.isPaused)
                .put("automationRemainedOff", !AppSettings.fullAutomation)
            output.resolve("report.json").writeText(report.toString(2) + "\n")
        }
    }

    /** Preserve complete annotation values and their JSON Pointer locations before stripping. */
    private fun collectDescriptions(value: Any?, toolName: String, path: String, output: JSONArray,
                                    nameMap: Boolean = false) {
        when (value) {
            is JSONObject -> for (key in value.keys().asSequence().toList().sorted()) {
                val childPath = path + "/" + key.replace("~", "~0").replace("/", "~1")
                if (key == "description" && !nameMap) {
                    output.put(JSONObject().put("tool", toolName).put("schemaPath", childPath)
                        .put("description", value.get(key)))
                } else {
                    val childNameMap = !nameMap && key in setOf(
                        "properties", "patternProperties", "\$defs", "definitions", "dependentSchemas", "dependentRequired"
                    )
                    collectDescriptions(value.opt(key), toolName, childPath, output, childNameMap)
                }
            }
            is JSONArray -> for (index in 0 until value.length())
                collectDescriptions(value.opt(index), toolName, "$path/$index", output)
        }
    }

    /** Remove schema annotations from a deep copy, preserving named parameters. */
    private fun removeDescriptions(value: Any?, nameMap: Boolean = false): Int {
        var removed = 0
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                for (key in keys) {
                    // A parameter/definition may itself be named "description";
                    // that is part of the schema, not an annotation to erase.
                    if (key == "description" && !nameMap) {
                        value.remove(key)
                        removed++
                    } else {
                        val childNameMap = !nameMap && key in setOf(
                            "properties", "patternProperties", "\$defs", "definitions", "dependentSchemas", "dependentRequired"
                        )
                        removed += removeDescriptions(value.opt(key), childNameMap)
                    }
                }
            }
            is JSONArray -> for (index in 0 until value.length()) removed += removeDescriptions(value.opt(index))
        }
        return removed
    }
}
