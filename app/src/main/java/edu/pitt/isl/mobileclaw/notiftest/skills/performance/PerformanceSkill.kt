package edu.pitt.isl.mobileclaw.notiftest.skills.performance

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.Skill
import edu.pitt.isl.mobileclaw.notiftest.Tool

/**
 * Inspects memory use and offers process controls through [ProcessProbe].
 *
 * The declared capability improves the available data and controls: both tools have
 * weaker fallbacks when no privileged backend is connected, so missing
 * [Capability.PRIVILEGED_SHELL] limits their results rather than disabling the skill.
 */
class PerformanceSkill : Skill {
    override val id = "performance"
    override val displayName = "Performance"
    override val summary =
        "Report what is using the most memory and stop apps you agree to stop. " +
            "Works better with privileged shell access; still answers without it."
    override val capabilities = listOf(Capability.PRIVILEGED_SHELL)

    override fun tools(context: Context): List<Tool> = listOf(
        MemoryMonitorTool(context),
        KillProcessTool(context),
    )
}
