package edu.pitt.isl.mobileclaw.notiftest.eval

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.pitt.isl.mobileclaw.notiftest.AgentEvent
import edu.pitt.isl.mobileclaw.notiftest.AgentRegistry
import edu.pitt.isl.mobileclaw.notiftest.CapturedNotif
import edu.pitt.isl.mobileclaw.notiftest.ChatMessage
import edu.pitt.isl.mobileclaw.notiftest.LlmConfig
import edu.pitt.isl.mobileclaw.notiftest.NotifStore
import edu.pitt.isl.mobileclaw.notiftest.OpenAiAiClient
import edu.pitt.isl.mobileclaw.notiftest.ReasoningModule
import edu.pitt.isl.mobileclaw.notiftest.SkillRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the production model paths against a configurable endpoint and writes one JSON
 * line per case, so a local model and the cloud baseline can be compared on identical
 * requests. Not a test in the pass/fail sense — it never asserts — and it makes live
 * calls that cost money or minutes, so it runs only when asked for by name:
 *
 *   am instrument -w -e eval true -e class ...eval.ModelEvalRunner \
 *      [-e endpoint URL] [-e model NAME] [-e apiKey KEY] [-e tag LABEL] \
 *      [-e images true] [-e only TASK_ID] [-e readTimeout SECONDS] [-e promptSuffix TEXT] \
 *      edu.pitt.isl.mobileclaw.notiftest.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Defaults come from LlmConfig, using the runtime profile saved in Settings > Model
 * service. With no endpoint/model/key overrides, that profile supplies the baseline.
 * Output lands in the app's external files dir
 * under eval/<tag>-<suite>.jsonl; tools/eval/run_eval.sh pulls and tools/eval/report.py
 * tabulates. Without `-e eval true` every case is skipped, which keeps a plain
 * connectedDebugAndroidTest from making a live call.
 *
 * Two things it deliberately shares with production rather than re-deriving: the
 * notification records (posted by NotificationTester and read back, see EvalNotifs) and
 * the tool catalog (SkillRegistry, wrapped by RecordingTool). What it replaces is only
 * the execution of tools that act or need a permission.
 */
@RunWith(AndroidJUnit4::class)
class ModelEvalRunner {

    private val args = InstrumentationRegistry.getArguments()
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val endpoint = args.getString("endpoint") ?: LlmConfig.endpoint
    private val model = args.getString("model") ?: LlmConfig.model
    private val apiKey = args.getString("apiKey") ?: LlmConfig.apiKey
    private val tag = args.getString("tag") ?: model.replace('/', '_')
    private val images = args.getString("images") == "true"
    private val only = args.getString("only")
    private val readTimeout = args.getString("readTimeout")?.toLongOrNull() ?: 600L
    /**
     * Text appended to the chat system prompt, so a candidate rule can be scored against
     * both models before anyone edits the production prompt. Recorded in every chat row.
     */
    private val promptSuffix = args.getString("promptSuffix")?.replace("\\n", "\n")
    private val outDir = File(ctx.getExternalFilesDir(null), "eval").apply { mkdirs() }

    private lateinit var captured: List<Pair<Int, CapturedNotif>>

    @Before
    fun setUp() {
        assumeTrue("pass -e eval true to run the model eval", args.getString("eval") == "true")
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        EvalNotifs.fireAll(ctx)
        val deadline = System.currentTimeMillis() + 5_000
        while (EvalNotifs.activeCount(ctx) < 16 && System.currentTimeMillis() < deadline) Thread.sleep(100)
        captured = EvalNotifs.captureOwn(ctx, images)
        // The chat path reads the shade through NotifStore — the same store the listener
        // fills — so the context block and read_notifications see these records.
        NotifStore.clear()
        captured.forEach { NotifStore.add(it.second) }
        Log.i(TAG, "eval tag=$tag model=$model endpoint=$endpoint notifs=${captured.size} images=$images")
    }

    @Test
    fun triage() = runBlocking {
        val rm = ReasoningModule(apiKey, endpoint, model, readTimeoutSeconds = readTimeout)
        val out = File(outDir, "$tag-triage.jsonl").apply { writeText("") }
        for ((case, n) in captured) {
            val t0 = System.currentTimeMillis()
            val d = rm.decide(n)
            val ms = System.currentTimeMillis() - t0
            val expected = EvalNotifs.EXPECTED.getValue(case)
            val rec = JSONObject()
                .put("suite", "triage").put("tag", tag).put("model", model)
                .put("case", case).put("label", EvalNotifs.LABEL[case])
                .put("expected", JSONArray(expected.map { it.name.lowercase() }))
                .put("ms", ms)
            if (d == null) {
                rec.put("skipped", "ongoing")
            } else {
                val failed = d.reasoning.startsWith("Reasoning failed")
                rec.put("action", d.action.name.lowercase())
                    .put("priority", d.priority.name.lowercase())
                    .put("confidence", d.confidence.toDouble())
                    .put("reasoning", d.reasoning)
                    .put("replyText", d.replyText)
                    .put("actionTitle", d.actionTitle)
                    .put("failed", failed)
                    .put("pass", !failed && d.action in expected)
            }
            out.appendText(rec.toString() + "\n")
            Log.i(TAG, rec.toString())
        }
    }

    @Test
    fun chat() = runBlocking {
        val log = mutableListOf<ToolCallRecord>()
        val tools = SkillRegistry.tools().map { RecordingTool(it, CannedTools.canned(it.name), log) }
        val client = OpenAiAiClient(
            apiKey = apiKey, endpoint = endpoint,
            contextProvider = { notifContext() },
            readTimeoutSeconds = readTimeout,
        )
        val base = AgentRegistry.generalChat
        val spec = base.copy(
            model = model, tools = tools,
            systemPrompt = if (promptSuffix.isNullOrBlank()) base.systemPrompt else base.systemPrompt + "\n\n" + promptSuffix,
        )
        val out = File(outDir, "$tag-chat.jsonl").apply { writeText("") }

        for (task in ChatTasks.ALL.filter { only == null || it.id == only }) {
            log.clear()
            val history = listOf(ChatMessage(role = ChatMessage.Role.USER, content = task.prompt))
            val text = StringBuilder()
            var done: ChatMessage? = null
            var error: String? = null
            var promptTokens = 0
            var completionTokens = 0
            var requests = 0
            var firstTokenMs = -1L
            val t0 = System.currentTimeMillis()
            client.run(spec, history).collect { ev ->
                when (ev) {
                    is AgentEvent.TokenDelta -> {
                        if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - t0
                        text.append(ev.text)
                    }
                    is AgentEvent.Usage -> { requests++; promptTokens += ev.promptTokens; completionTokens += ev.completionTokens }
                    is AgentEvent.Done -> done = ev.message
                    is AgentEvent.Error -> error = ev.error.message
                    else -> {}
                }
            }
            val ms = System.currentTimeMillis() - t0
            val calls = log.toList()
            val called = calls.map { it.name }.toSet()
            val answer = done?.content ?: text.toString()

            val expectOk = task.expect.all { alt -> alt.any { it in called } }
            val forbidOk = task.forbid.none { it in called }
            val argsOk = task.argsContain.all { (tool, subs) ->
                calls.filter { it.name == tool }.any { c -> subs.all { s -> c.args.contains(s, ignoreCase = true) } }
            }
            val answerOk = task.answerContainsAny.isEmpty() || task.answerContainsAny.any { answer.contains(it, ignoreCase = true) }

            val rec = JSONObject()
                .put("suite", "chat").put("tag", tag).put("model", model)
                .put("task", task.id).put("prompt", task.prompt)
                .put("promptSuffix", promptSuffix ?: JSONObject.NULL)
                .put("ms", ms).put("firstTokenMs", firstTokenMs)
                .put("requests", requests).put("promptTokens", promptTokens).put("completionTokens", completionTokens)
                .put("calls", JSONArray(calls.map { JSONObject().put("name", it.name).put("args", it.args) }))
                .put("answer", answer)
                .put("actions", JSONArray(done?.actions ?: emptyList<String>()))
                .put("error", error ?: JSONObject.NULL)
                .put("expectOk", expectOk).put("forbidOk", forbidOk).put("argsOk", argsOk).put("answerOk", answerOk)
                .put("pass", error == null && expectOk && forbidOk && argsOk && answerOk)
            out.appendText(rec.toString() + "\n")
            Log.i(TAG, "${task.id}: pass=${rec.getBoolean("pass")} ms=$ms calls=${calls.map { it.name }}")
        }
    }

    /** Same rendering as MainViewModel.buildNotifContext, which is private to the ViewModel. */
    private fun notifContext(): String {
        val active = NotifStore.events.value.filter { it.posted }
        if (active.isEmpty()) return "No notifications are currently captured."
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        val lines = active.asReversed().take(25).mapIndexed { i, n ->
            buildString {
                append("  [${i + 1}] ${fmt.format(Date(n.postTimeMs))} · ${n.appName}")
                if (n.title.isNotBlank()) append(" — ${ReasoningModule.redact(n.title)}")
                val body = ReasoningModule.redact(n.text.take(120).replace('\n', ' '))
                if (body.isNotBlank()) append(": $body")
                if (n.actions.isNotEmpty()) append("  (${n.actions.joinToString(", ") { it.title }})")
                if (n.isOngoing) append("  [ongoing]")
                if (n.progressMax > 0) append("  [${n.progressCurrent}/${n.progressMax}]")
            }
        }.joinToString("\n")
        return "Captured notifications (${active.size} total, newest first):\n$lines"
    }

    private companion object { const val TAG = "MobileClaw-EV" }
}
