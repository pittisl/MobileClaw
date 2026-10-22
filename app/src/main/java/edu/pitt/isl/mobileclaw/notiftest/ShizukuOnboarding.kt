package edu.pitt.isl.mobileclaw.notiftest

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.shizuku.ShizukuProvider

/**
 * Shared privileged-shell onboarding for [MainActivity] and [SettingsActivity].
 *
 * The flow distinguishes three states:
 * - A Shizuku binder is available: request the user's grant.
 * - Shizuku is installed but no binder is available: its server may be stopped, or
 *   this process may have started before the binder arrived. Offer an app or Shizuku
 *   restart rather than directing the user to reinstall.
 * - Shizuku is absent: open its installation page.
 */
object ShizukuOnboarding {

    fun prompt(activity: Activity, cap: Capability = Capability.PRIVILEGED_SHELL) {
        if (ShizukuBridge.isShizukuRunning()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("${cap.label} needed")
                .setMessage("${cap.rationale}\n\nShizuku is running. Allow MobileClaw to use it?")
                .setPositiveButton("Allow") { _, _ ->
                    if (!ShizukuBridge.requestPermission()) {
                        Toast.makeText(activity, "Shizuku could not be asked — check it's up to date", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Not now", null)
                .show()
            return
        }

        if (isManagerInstalled(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("${cap.label} not connected")
                .setMessage(
                    "${cap.rationale}\n\nShizuku is installed but hasn't connected to " +
                    "MobileClaw. If Shizuku shows itself as running, close MobileClaw " +
                    "fully and open it again. Otherwise start Shizuku (over ADB or " +
                    "wireless debugging; it must be restarted after every reboot unless " +
                    "the device is rooted) and try again.\n\nEverything else in the app " +
                    "works normally without it."
                )
                .setPositiveButton("Open Shizuku") { _, _ ->
                    val launch = activity.packageManager.getLaunchIntentForPackage(ShizukuProvider.MANAGER_APPLICATION_ID)
                    if (launch == null || runCatching { activity.startActivity(launch) }.isFailure) {
                        Toast.makeText(activity, "Couldn't open Shizuku", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Not now", null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("${cap.label} not connected")
            .setMessage(
                "${cap.rationale}\n\nShizuku isn't installed. Install it, start it once over " +
                "ADB or wireless debugging, then try again. It must be restarted after " +
                "every reboot unless your device is rooted.\n\nEverything else in the app " +
                "works normally without it."
            )
            .setPositiveButton("Get Shizuku") { _, _ ->
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuBridge.SHIZUKU_PLAY_URL)))
                }.onFailure {
                    Toast.makeText(activity, "No browser or store app available", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /**
     * Needs the `<queries>` entry in the manifest: on API 30+ a package the app hasn't
     * declared an interest in is invisible, and this would answer "not installed" for
     * every device — collapsing the middle state into the last one.
     */
    private fun isManagerInstalled(activity: Activity): Boolean = try {
        activity.packageManager.getPackageInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
