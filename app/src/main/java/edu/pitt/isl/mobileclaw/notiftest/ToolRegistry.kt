package edu.pitt.isl.mobileclaw.notiftest

/**
 * Catalog of agent tools, as the agent sees them: one flat list, keyed by name.
 *
 * Tools are contributed by [Skill]s and built by [SkillRegistry]. This flattened
 * view exposes individual [Tool]s to `AgentSpec.tools`, `AiClient` and tests at
 * the model boundary.
 *
 * Register a [Skill] via [SkillRegistry.register], not a
 * loose tool. A tool on its own has no declared permissions and nothing to show a user.
 */
object ToolRegistry {

    fun all(): List<Tool> = SkillRegistry.tools()

    fun byName(name: String): Tool? = all().firstOrNull { it.name == name }
}
