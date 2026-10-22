package edu.pitt.isl.mobileclaw.notiftest.skills.appfunctions

import edu.pitt.isl.mobileclaw.notiftest.Redaction
import edu.pitt.isl.mobileclaw.notiftest.ShellGate
import edu.pitt.isl.mobileclaw.notiftest.Tool
import edu.pitt.isl.mobileclaw.notiftest.ToolInvocation
import org.json.JSONException
import org.json.JSONObject

/**
 * `call_app_function` — one AppFunction call, staged for confirmation.
 *
 * The tool builds the exact nine-element vector `ShellCommandPolicy.rejectCmd` admits and
 * hands it to [ShellGate.modify]; it cannot execute anything itself. Parameters are
 * re-serialised from the parsed object, so what crosses the bridge is a JSON object the
 * app produced, not the model's text. The proposal the user sees names the package, the
 * function and the parameters (redacted — they are model-generated and may echo device
 * text), because "Call a function in ChatApp" would be a confirm dialog with nothing to
 * decide on.
 */
class CallAppFunctionTool : Tool {
    override val requiresConfirmationReceipt = true

    override val name = "call_app_function"
    override val description =
        "Invoke a function another app exposes on Android's AppFunctions bus (Android 16+). " +
            "Runs only after the user confirms; the return value is reported afterwards. Every parameter " +
            "a function declares must be present (pass \"\" for an optional text). Known functions:\n" +
            "TaskBoard (package $TASKBOARD_PACKAGE, a task-list app with a coloured header): " +
            "$TASKBOARD_CLASS#addTask {title, note} adds a task and returns it with its id; " +
            "$TASKBOARD_CLASS#completeTask {id (integer)} marks it done and says so, or says why not; " +
            "$TASKBOARD_CLASS#listTasks {} lists tasks with ids; " +
            "$TASKBOARD_CLASS#setThemeColor {color} — a name (blue, red, orange, yellow, green, teal, purple, " +
            "pink, grey, black) or #RRGGBB — recolours the app; " +
            "$TASKBOARD_CLASS#postNotification {title, text} posts a notification from TaskBoard; " +
            "$TASKBOARD_CLASS#getBoard {} reports theme colour and task counts.\n" +
            "ChatApp (package $CHATAPP_PACKAGE): $CHATAPP_SERVICE#searchContacts {query, contactType} — " +
            "contactType is one of INDIVIDUAL, GROUP, ANY (use ANY unless told otherwise); returns contacts " +
            "with an endpointValue. $CHATAPP_SERVICE#sendMessage {endpointValue, messageBody} sends a chat " +
            "message; call searchContacts first to get the endpointValue. " +
            "$CHATAPP_SERVICE#searchMessages {query, endpointValue?}."
    override val parameters = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "package": { "type": "string", "description": "Package name of the app that exposes the function, e.g. $CHATAPP_PACKAGE." },
            "function_id": { "type": "string", "description": "Function identifier as Class#method, e.g. $CHATAPP_SERVICE#searchContacts." },
            "parameters": { "type": "object", "description": "The function's parameters as a JSON object, keyed by parameter name." }
          },
          "required": ["package", "function_id", "parameters"]
        }
        """.trimIndent()
    )

    override suspend fun invoke(args: JSONObject): String = invokeWithReceipt(args).text

    override suspend fun invokeWithReceipt(args: JSONObject): ToolInvocation {
        val pkg = args.optString("package").trim()
        val id = args.optString("function_id").trim()
        val params: JSONObject = when (val p = args.opt("parameters")) {
            is JSONObject -> p
            is String -> try { JSONObject(p) } catch (_: JSONException) { return ToolInvocation.NotExecuted("parameters must be a JSON object.") }
            null -> JSONObject()
            else -> return ToolInvocation.NotExecuted("parameters must be a JSON object.")
        }
        if (pkg.isEmpty() || id.isEmpty()) return ToolInvocation.NotExecuted("package and function_id are required.")

        val argv = listOf(
            "cmd", "app_function", "execute-app-function",
            "--package", pkg, "--function", id, "--parameters", params.toString(),
        )
        val shown = Redaction.redact(params.toString())
        return ShellGate.modifyWithReceipt(
            SOURCE, "app_function", argv,
            description = "Call app function in $pkg:\n${id.substringAfterLast('#')} $shown",
            reply = "Ready to call ${id.substringAfterLast('#')} in $pkg — waiting for your confirmation. " +
                "The function's return value will be reported once it runs.",
            // The platform prints its return value as a GenericDocument dump; the model
            // gets the value, the audit trail keeps the dump (ShellGate records raw output).
            present = { AppFunctionResult.render(id, it) },
            isSuccessful = { it.ok && AppFunctionResult.executionConfirmed(it.stdout) },
        )
    }

    companion object {
        const val SOURCE = "call_app_function"
        const val CHATAPP_PACKAGE = "com.example.chatapp"
        const val CHATAPP_SERVICE = "com.example.chatapp.appfunctions.BaseChatAppFunctionService"
        const val TASKBOARD_PACKAGE = "edu.pitt.isl.mobileclaw.taskboard"
        const val TASKBOARD_CLASS = "edu.pitt.isl.mobileclaw.taskboard.TaskBoardFunctions"
    }
}
