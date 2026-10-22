package edu.pitt.isl.mobileclaw.notiftest.skills.context

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.Skill
import edu.pitt.isl.mobileclaw.notiftest.Tool

/**
 * Ambient facts about the device the model would otherwise guess at: what time it is,
 * and what the user has been doing.
 *
 * `get_current_time` needs nothing and `usage_context` needs a special access, which
 * makes this the one bundle where the declared capability covers only part of it —
 * the clock keeps working when usage access is refused.
 */
class ContextSkill : Skill {
    override val id = "context"
    override val displayName = "Device context"
    override val summary =
        "Tell the time, and report which apps you've been using recently."
    override val capabilities = listOf(Capability.USAGE_ACCESS)

    override fun tools(context: Context): List<Tool> = listOf(
        GetCurrentTimeTool(),
        UsageContextTool(context),
    )
}
