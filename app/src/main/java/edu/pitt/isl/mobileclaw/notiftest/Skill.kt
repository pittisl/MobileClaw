package edu.pitt.isl.mobileclaw.notiftest

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.skills.appfunctions.AppFunctionsSkill
import edu.pitt.isl.mobileclaw.notiftest.skills.calendar.CalendarSkill
import edu.pitt.isl.mobileclaw.notiftest.skills.context.ContextSkill
import edu.pitt.isl.mobileclaw.notiftest.skills.filesystem.FileSystemSkill
import edu.pitt.isl.mobileclaw.notiftest.skills.memory.MemorySkill
import edu.pitt.isl.mobileclaw.notiftest.skills.messaging.MessagingSkill
import edu.pitt.isl.mobileclaw.notiftest.skills.music.MusicSkill
import edu.pitt.isl.mobileclaw.notiftest.skills.notifications.NotificationsSkill
import edu.pitt.isl.mobileclaw.notiftest.skills.notion.NotionSkill
import edu.pitt.isl.mobileclaw.notiftest.skills.performance.PerformanceSkill
import edu.pitt.isl.mobileclaw.notiftest.skills.search.SearchSkill

/**
 * A bundle of related agent capability: one or more [Tool]s, the device
 * [Capability]s they reach for, and the metadata that describes the bundle to a
 * human.
 *
 * [Tool] stays the unit the *model* sees — a name, a schema, a result. A Skill is
 * the unit everything else wants: what to show in Settings, which permissions to
 * ask for together, and how to describe related abilities.
 *
 * **A Skill declares tools; it never carries an execution path of its own.**
 * [PendingAction], [Capability], [ReasoningModule.redact], `ShellGate` and
 * `ShellCommandPolicy` all live outside the skill boundary and stay there. A skill
 * that could decide it didn't need confirmation would turn both cross-cutting gates
 * into suggestions.
 *
 * Implementations live one per package under `skills/`, so the import list at the
 * top of a skill's files is the statement of what it reaches into.
 */
interface Skill {
    /** Stable identifier. Used in Settings and, later, as the key for enablement. */
    val id: String

    /** Human-facing name, e.g. "Music control". */
    val displayName: String

    /**
     * One line describing what the skill does, written for the *user*. Tool
     * descriptions are written for the model and are a different register — this one
     * appears in Settings.
     */
    val summary: String

    /**
     * Every [Capability] this skill's tools touch. Declarative rather than discovered:
     * the checks still happen inside each `invoke` (that is where the fallbacks and
     * the [PermissionSignal] live). The declaration lets callers explain what each
     * capability enables without inspecting tool implementation details.
     *
     * Declaring one does not mean the skill is useless without it — `performance`
     * degrades to weaker sources rather than failing, per capability fallbacks.
     */
    val capabilities: List<Capability>

    /**
     * The tools this skill contributes. Built once by [SkillRegistry] with an
     * application context.
     */
    fun tools(context: Context): List<Tool>
}

/**
 * The registration point for skills, and thereby for tools — [ToolRegistry] is a
 * derived view over this.
 *
 * Registering a whole skill rather than a loose tool is what makes the metadata above
 * mandatory: a tool arriving on its own could have no owner, no declared permissions
 * and nothing to show a user.
 */
object SkillRegistry {

    private val skills = mutableListOf<Skill>()
    private val toolsBySkill = mutableMapOf<String, List<Tool>>()
    private var appContext: Context? = null

    /** Idempotent: registers the built-in skills. Safe to call multiple times. */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        // Order matters only in that it is the order the model sees the tool catalog in;
        // reads before actions, on the theory that a model scanning a list should meet
        // the harmless half first.
        listOf(
            NotificationsSkill(),
            MessagingSkill(),
            CalendarSkill(),
            MusicSkill(),
            PerformanceSkill(),
            FileSystemSkill(),
            ContextSkill(),
            SearchSkill(),
            MemorySkill(),
            NotionSkill(),
            AppFunctionsSkill(),
        ).forEach { add(app, it) }
    }

    /**
     * Registers an extra skill at runtime as a bundle of tools, declared capabilities
     * and a human-readable description.
     */
    @Synchronized
    fun register(skill: Skill) {
        val app = checkNotNull(appContext) { "SkillRegistry.init must run before register" }
        add(app, skill)
    }

    @Synchronized
    fun all(): List<Skill> = skills.toList()

    @Synchronized
    fun byId(id: String): Skill? = skills.firstOrNull { it.id == id }

    /** Every registered skill's tools, flattened — what the agent is handed. */
    @Synchronized
    fun tools(): List<Tool> = skills.flatMap { toolsBySkill[it.id].orEmpty() }

    /** The tools of one skill, for Settings and tests. */
    @Synchronized
    fun toolsOf(skillId: String): List<Tool> = toolsBySkill[skillId].orEmpty()

    private fun add(app: Context, skill: Skill) {
        // Keep the first registration for an id so a duplicate cannot silently
        // replace the implementation already supplied to the agent.
        if (skills.any { it.id == skill.id }) return
        skills += skill
        toolsBySkill[skill.id] = skill.tools(app)
    }
}
