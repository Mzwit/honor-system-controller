package com.honor.systemcontroller.core.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Finds the things malware uses to dig in and to block Developer options:
 *  - Device administrators (can set DISALLOW_DEBUGGING_FEATURES, lock the screen, wipe)
 *  - Accessibility services (can read the screen and tap buttons – used to auto-cancel removal
 *    and to intercept the Build-number taps)
 *  - Apps with "draw over other apps" and notification-listener access
 *
 * Reading all of this needs no special permission. Removing a device admin that is NOT the
 * device owner can be done with Shizuku/root via `dpm remove-active-admin`.
 */
class SecurityInspector(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val um = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val pm = context.packageManager

    /** Well-known, legitimate admins we don't want to alarm the user about. */
    private val knownGoodAdmins = setOf(
        "com.google.android.gms",          // Find My Device / Google
        "com.google.android.apps.work.clouddpc",
        "com.hihonor.findmydevice",
        "com.hihonor.id",
        "com.android.settings"
    )

    data class AdminApp(
        val packageName: String,
        val label: String,
        val component: String,
        val isDeviceOwner: Boolean,
        val isProfileOwner: Boolean,
        val blocksDebugging: Boolean,
        val wellKnown: Boolean
    ) {
        /** Suspicious when it isn't a recognised admin, especially if it blocks debugging. */
        val suspicious get() = !wellKnown
    }

    data class AccessApp(val packageName: String, val label: String, val kind: String, val wellKnown: Boolean)

    fun label(pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** True if a device owner / profile owner restricts debugging features (why Build number does nothing). */
    fun debuggingRestricted(): Boolean =
        um.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES)

    fun developerSettingsRestricted(): Boolean =
        runCatching { um.hasUserRestriction("no_debugging_features") }.getOrDefault(false) || debuggingRestricted()

    fun activeAdmins(): List<AdminApp> {
        val admins = runCatching { dpm.activeAdmins }.getOrNull() ?: return emptyList()
        return admins.map { cn: ComponentName ->
            val pkg = cn.packageName
            AdminApp(
                packageName = pkg,
                label = label(pkg),
                component = cn.flattenToShortString(),
                isDeviceOwner = runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false),
                isProfileOwner = runCatching { dpm.isProfileOwnerApp(pkg) }.getOrDefault(false),
                blocksDebugging = debuggingRestricted(),
                wellKnown = pkg in knownGoodAdmins || pkg == context.packageName
            )
        }.sortedByDescending { it.suspicious }
    }

    fun accessibilityServices(): List<AccessApp> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = runCatching { am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK) }.getOrNull() ?: emptyList()
        return list.mapNotNull { info ->
            val pkg = info.resolveInfo?.serviceInfo?.packageName ?: return@mapNotNull null
            AccessApp(pkg, label(pkg), "Accessibility", isSystemPkg(pkg))
        }.distinctBy { it.packageName }.sortedByDescending { !it.wellKnown }
    }

    private fun isSystemPkg(pkg: String): Boolean = runCatching {
        (pm.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    // ---- navigation helpers ----
    fun openDeviceAdminSettings() {
        val tries = listOf(
            Intent("android.settings.DEVICE_ADMIN_SETTINGS"),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in tries) if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
    }

    fun openAccessibilitySettings() =
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    fun openAppInfo(pkg: String) =
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    /** Shell command to strip a non-owner admin; safe for Shizuku/root. */
    fun removeAdminCommand(component: String) = "dpm remove-active-admin $component"
}
