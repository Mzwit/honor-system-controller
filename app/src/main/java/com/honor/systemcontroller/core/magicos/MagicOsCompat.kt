package com.honor.systemcontroller.core.magicos

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * HONOR / MagicOS specifics.
 *
 * MagicOS has its own "App launch" manager (inside Phone Manager, com.hihonor.systemmanager)
 * that controls auto-launch, secondary launch and background running independently of AOSP
 * battery optimisation. Its activity names are not public API and change between releases,
 * so every shortcut below is tried in order and falls back to a standard Settings screen.
 */
object MagicOsCompat {

    fun isHonor(): Boolean = Build.MANUFACTURER.equals("HONOR", true) || Build.BRAND.equals("HONOR", true)

    fun magicOsVersion(): String? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        val get = c.getMethod("get", String::class.java)
        listOf("ro.build.version.magic", "ro.build.version.magicui", "msc.config.magic.version", "ro.build.version.emui")
            .map { get.invoke(null, it) as String }.firstOrNull { it.isNotBlank() }
    }.getOrNull()

    fun deviceSummary(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
        (magicOsVersion()?.let { " · $it" } ?: "")

    private val appLaunchCandidates = listOf(
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.hihonor.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ComponentName("com.hihonor.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
    )

    /** Opens MagicOS "App launch" management, or the best standard alternative. Returns what was opened. */
    fun openAppLaunchManager(context: Context): String {
        for (c in appLaunchCandidates) {
            val i = Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i); true }.getOrDefault(false)) return "MagicOS App launch manager"
        }
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(fallback); true }.getOrDefault(false)) "Battery optimisation settings" else "Nothing could be opened"
    }

    fun openBatteryUsage(context: Context) {
        val i = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openUsageAccess(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openDeveloperOptions(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ---------- Developer options / debugging state (readable by any app) ----------
    private fun global(context: Context, key: String): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, key, 0) }.getOrDefault(0)

    fun developerOptionsEnabled(context: Context) = global(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
    fun usbDebuggingEnabled(context: Context) = global(context, Settings.Global.ADB_ENABLED) == 1
    fun wirelessDebuggingEnabled(context: Context) = global(context, "adb_wifi_enabled") == 1

    /** Shell commands that switch everything on (need Shizuku/ADB shell or root: WRITE_SECURE_SETTINGS). */
    val enableDebugCommands = listOf(
        "settings put global development_settings_enabled 1",
        "settings put global adb_enabled 1",
        "settings put global adb_wifi_enabled 1"
    )

    /** "About phone" – where Build number must be tapped 7 times. MagicOS tries its own screen first. */
    fun openAboutPhone(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$MyDeviceInfoActivity")),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in candidates) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
        }
    }

    /** Opens Developer options scrolled to the Wireless debugging row where supported. */
    fun openWirelessDebugging(context: Context) {
        val i = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure { openDeveloperOptions(context) }
    }

    fun openShizuku(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    /** This app itself must survive MagicOS background killing for automation to run. */
    fun isSelfBatteryUnrestricted(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    val notes: List<Pair<String, String>> = listOf(
        "App launch manager" to "MagicOS 'App launch' (Settings › Battery › App launch) can auto-relaunch apps or kill this app's scheduler. Set HONOR System Controller to 'Manage manually' with all three toggles on so automation keeps running.",
        "Shizuku after reboot" to "Non-root Shizuku stops on every reboot. Restart it from the Shizuku app (Wireless debugging must be on). Disabled/blocked states persist anyway – only re-checks need it.",
        "Wireless debugging" to "MagicOS turns Wireless debugging off when Wi-Fi changes network. Re-enable it in Developer options before starting Shizuku.",
        "Protected HONOR packages" to "Some com.hihonor.* packages are marked non-disableable by the system; `pm disable-user` returns an error for them. That error is shown verbatim and nothing is recorded as successful.",
        "Power Genie" to "MagicOS's own power manager may restart HONOR services that were force-stopped. Use Disable (persistent) rather than repeated force-stops.",
        "Background restriction" to "MagicOS's battery 'Manage manually' toggles and Android appops are separate layers; this app sets appops + standby bucket and shows a shortcut to the MagicOS screen for the other layer."
    )
}
