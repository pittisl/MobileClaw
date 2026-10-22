package edu.pitt.isl.mobileclaw.notiftest

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * A device capability an agent tool depends on, together with how to check and
 * grant it. Centralising this here keeps every new tool's "is this available?"
 * check and its onboarding path identical.
 *
 * Two flavours:
 *  - **Runtime** capabilities back a dangerous manifest permission granted via a
 *    system permission dialog ([manifestPermission] non-null).
 *  - **Special-access** capabilities ([manifestPermission] == null) are granted
 *    from a dedicated Settings page and checked bespoke (overlay, usage stats,
 *    notification access).
 */
enum class Capability(
    val label: String,
    /** One-line rationale shown in the onboarding prompt. */
    val rationale: String,
    /** Dangerous runtime permission, or null for special-access capabilities. */
    val manifestPermission: String?
) {
    NOTIFICATION_ACCESS(
        "Notification access",
        "Read notifications and control media so the agent can triage and act on them.",
        null
    ),
    SEND_SMS(
        "Send SMS",
        "Send text messages on your behalf (always behind a confirmation).",
        android.Manifest.permission.SEND_SMS
    ),
    READ_CONTACTS(
        "Contacts",
        "Turn names like \"Mom\" into phone numbers and email addresses.",
        android.Manifest.permission.READ_CONTACTS
    ),
    READ_CALENDAR(
        "Read calendar",
        "Answer questions about your upcoming events.",
        android.Manifest.permission.READ_CALENDAR
    ),
    WRITE_CALENDAR(
        "Create events",
        "Add events to your calendar (always behind a confirmation).",
        android.Manifest.permission.WRITE_CALENDAR
    ),
    USAGE_ACCESS(
        "App usage access",
        "See which apps you've used recently for better context.",
        null
    ),
    /**
     * "All files access" (`MANAGE_EXTERNAL_STORAGE`), the special access that lets the
     * file-search index read shared storage directly under scoped storage.
     * Declared as special access rather than a runtime permission: on API 30+ that is
     * what it is, granted from its own Settings page. On API 26–29 the equivalent is the
     * `READ_EXTERNAL_STORAGE` runtime permission, checked below and granted from the app's
     * details page — an accepted rough edge, since no device this project targets is
     * below 30.
     *
     * Not `PRIVILEGED_SHELL`: this is the app reading files as itself, and it must stay
     * distinguishable from the bridge, which is what *modifies* them.
     */
    ALL_FILES_ACCESS(
        "All files access",
        "Let the file-search index read the files on your phone's shared storage. Read-only.",
        null
    ),
    OVERLAY(
        "Display over other apps",
        // Not "…so actions aren't dropped" any more: without this they arrive as a
        // notification (NotifConfirm), so the honest pitch is where the prompt appears,
        // not whether one appears at all. Overstating what a permission buys is how a
        // user ends up granting a special access they didn't need.
        "Show the confirmation prompt over whatever you're doing, instead of sending it to the notification shade.",
        null
    ),

    /**
     * Not a permission at all — it is whether a privileged backend is currently
     * connected ([ShellRegistry]). It rides in this enum anyway so that tools reach it
     * through the machinery they already use: `isGranted` / `Capability.unavailable` /
     * `PermissionSignal.request`. Nothing about the `Tool` contract changes to support
     * privileged operations.
     *
     * Consequence worth knowing: unlike every other entry, this one can go from
     * granted to not granted without the user doing anything — a reboot kills every
     * planned backend.
     */
    PRIVILEGED_SHELL(
        "Privileged shell access",
        "Run system-level commands (file access outside app storage, process control). " +
            "Needs a helper started over ADB or Shizuku; it is not a Settings toggle.",
        null
    );

    /** True when this capability is currently usable on [context]. */
    fun isGranted(context: Context): Boolean = when (this) {
        NOTIFICATION_ACCESS -> isNotificationAccessGranted(context)
        USAGE_ACCESS        -> hasUsageAccess(context)
        OVERLAY             -> Settings.canDrawOverlays(context)
        ALL_FILES_ACCESS    ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.os.Environment.isExternalStorageManager()
            else ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        // Deliberately ignores [context]: "granted" here means a backend answered, not
        // that anything was granted to this process.
        PRIVILEGED_SHELL    -> ShellRegistry.isConnected
        else -> {
            val perm = manifestPermission
            perm == null || ContextCompat.checkSelfPermission(context, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {

        /**
         * A structured "capability unavailable" result a tool returns when its
         * permission is missing. The model can reason about this (tell the user,
         * suggest granting) rather than crashing or hallucinating success. The app
         * separately surfaces a real grant prompt via [PermissionSignal].
         */
        fun unavailable(cap: Capability): String =
            "[capability_unavailable: \"${cap.label}\" is not granted. " +
                "${cap.rationale} I've asked the app to prompt the user to grant it — " +
                "tell them briefly, then try again once granted.]"

        fun isNotificationAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return flat.split(":").any {
                it.isNotEmpty() &&
                    ComponentName.unflattenFromString(it)?.packageName == context.packageName
            }
        }

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
