package edu.pitt.isl.mobileclaw.taskboard

import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.service.AppFunction

/** A task as it crosses the bus. */
@AppFunctionSerializable
data class TaskInfo(val id: Int, val title: String, val note: String, val done: Boolean)

/** The board at a glance. */
@AppFunctionSerializable
data class BoardState(val themeColor: String, val openTasks: Int, val doneTasks: Int, val taskTitles: List<String>)

/**
 * The functions TaskBoard exposes on Android 16's AppFunctions bus. Every
 * one has an effect a person can see on the TaskBoard screen or in the shade, which is
 * what makes them a demonstration and not a ping: MobileClaw on the same phone calls
 * `setThemeColor("purple")` and this app's header turns purple in front of the visitor.
 *
 * Enabled by default, unlike MobileClaw's own `ping`: this app exists to be driven, and
 * has nothing to protect. Identifiers are what the compiler assigns —
 * `edu.pitt.isl.mobileclaw.taskboard.TaskBoardFunctions#<method>` — and the caller
 * (MobileClaw's `call_app_function`) names them in its tool description, since the
 * Android 16 build offers callers no discovery. Failures the caller should be able to act
 * on are **returned as text, not thrown**: the platform forwards a thrown exception to the
 * caller as a bare code (`1500 - AppFunctionException`) and drops the
 * message, so "no task with id 9; call listTasks for the ids" only reaches the model as a
 * return value. Exceptions are kept for the caller's own mistakes (a blank title).
 *
 * Every call is logged on the board's screen, so the visitor also sees *what arrived*.
 * Not who from: alpha08's `AppFunctionContext` carries only a `Context`, no calling
 * package — which for MobileClaw over the bridge would be `com.android.shell`, uid 2000,
 * which identifies the privileged bridge rather than the initiating app.
 */
class TaskBoardFunctions {

    /**
     * Adds a task to the board.
     *
     * @param title the task, a few words
     * @param note optional detail; pass an empty string for none
     * @return the task as stored, with its id
     */
    @AppFunction
    fun addTask(appFunctionContext: AppFunctionContext, title: String, note: String): TaskInfo {
        if (title.isBlank()) throw AppFunctionInvalidArgumentException("title must not be empty")
        val board = Board(appFunctionContext.context)
        val task = board.addTask(title, note)
        board.logCall("addTask → #${task.id} ${task.title}")
        return task.info()
    }

    /**
     * Marks a task done.
     *
     * @param id the task id, from addTask or listTasks
     * @return what happened — the task marked done, or why not
     */
    @AppFunction
    fun completeTask(appFunctionContext: AppFunctionContext, id: Int): String {
        val board = Board(appFunctionContext.context)
        val task = board.completeTask(id)
        if (task == null) {
            board.logCall("completeTask → no task #$id")
            return "no task with id $id; call listTasks for the ids"
        }
        board.logCall("completeTask → #${task.id} done")
        return "task #${task.id} '${task.title}' marked done"
    }

    /**
     * Lists every task on the board, open and done.
     *
     * @return the tasks, oldest first
     */
    @AppFunction
    fun listTasks(appFunctionContext: AppFunctionContext): List<TaskInfo> {
        val board = Board(appFunctionContext.context)
        board.logCall("listTasks")
        return board.tasks().map { it.info() }
    }

    /**
     * Changes the board's theme colour, visible at once on the TaskBoard screen.
     *
     * @param color a colour name (blue, red, orange, yellow, green, teal, purple, pink, grey, black) or #RRGGBB
     * @return the colour applied as #RRGGBB, or why the request was refused
     */
    @AppFunction
    fun setThemeColor(appFunctionContext: AppFunctionContext, color: String): String {
        val board = Board(appFunctionContext.context)
        val hex = ThemeColor.parse(color)
        if (hex == null) {
            board.logCall("setThemeColor → unknown colour '$color'")
            // Single quotes on purpose: the platform prints return values unescaped, and a
            // double quote inside one is a parsing hazard for whoever reads the dump.
            return "'$color' is not a colour I know; use one of ${ThemeColor.names()} or #RRGGBB"
        }
        board.setThemeColor(hex)
        board.logCall("setThemeColor → $hex")
        return "theme colour is now $hex"
    }

    /**
     * Posts a notification from TaskBoard.
     *
     * @param title the notification title
     * @param text the notification body
     * @return what happened, including the notification id
     */
    @AppFunction
    fun postNotification(appFunctionContext: AppFunctionContext, title: String, text: String): String {
        if (title.isBlank()) throw AppFunctionInvalidArgumentException("title must not be empty")
        val context = appFunctionContext.context
        val board = Board(context)
        if (!Notifier.allowed(context)) {
            board.logCall("postNotification → refused, no permission")
            return "TaskBoard is not allowed to post notifications on this phone; open TaskBoard once and grant it, then try again"
        }
        val id = Notifier.post(context, title, text)
        board.logCall("postNotification → #$id '$title'")
        return "posted notification #$id: '$title' — $text"
    }

    /**
     * The board's current state: theme colour and task counts.
     *
     * @return the state
     */
    @AppFunction
    fun getBoard(appFunctionContext: AppFunctionContext): BoardState {
        val board = Board(appFunctionContext.context)
        val tasks = board.tasks()
        board.logCall("getBoard")
        return BoardState(board.themeColor(), tasks.count { !it.done }, tasks.count { it.done }, tasks.map { it.title })
    }

    private fun Task.info() = TaskInfo(id, title, note, done)
}
