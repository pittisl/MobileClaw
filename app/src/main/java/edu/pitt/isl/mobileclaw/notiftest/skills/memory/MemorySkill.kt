package edu.pitt.isl.mobileclaw.notiftest.skills.memory

import android.content.Context
import edu.pitt.isl.mobileclaw.notiftest.Capability
import edu.pitt.isl.mobileclaw.notiftest.Skill
import edu.pitt.isl.mobileclaw.notiftest.Tool

/**
 * What the agent knows about the user, as opposed to what it did.
 *
 * The only skill in the app that declares **no** [Capability]. That is not an omission:
 * memory reaches no permission-gated Android API and no privileged bridge — it reads and
 * writes one table in the app's own database. The risk it carries is of a completely
 * different kind, and no capability check would have addressed it, which is why memory needs a separate
 * trust boundary: a hostile notification that talks
 * the model into writing a memory has achieved prompt injection *that persists*, surviving
 * the turn, the process and the reboot. The defences against that live outside this
 * boundary — in `MemoryPolicy`, `MemoryStore` and `AgentTurn` — exactly like
 * `PendingAction` and `Capability` do for every other skill.
 *
 * Also the only skill whose tools are **not** routed through `PendingAction`, deliberately. Writing a memory is inward, reversible and visible in Settings, and a confirm
 * dialog per remembered fact would train the user to approve without reading — which
 * weakens the gate for the things that genuinely need it, like sending an SMS or deleting
 * a directory. Bulk deletion *is* confirmed, and lives in Settings with the other
 * destructive actions.
 */
class MemorySkill : Skill {
    override val id = "memory"
    override val displayName = "Memory"
    override val summary =
        "Remember things about you across conversations, and look them up later."
    override val capabilities = emptyList<Capability>()

    override fun tools(context: Context): List<Tool> = listOf(
        RecallMemoryTool(),
        RememberTool(),
        ForgetMemoryTool(),
    )
}
