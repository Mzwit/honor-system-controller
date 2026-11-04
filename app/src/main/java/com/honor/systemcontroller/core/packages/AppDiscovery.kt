package com.honor.systemcontroller.core.packages

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import com.honor.systemcontroller.core.classify.Classification
import com.honor.systemcontroller.core.classify.SystemPackageClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val installType: String,
    val installer: String?,
    val enabledSetting: Int,
    val enabled: Boolean,
    val suspended: Boolean,
    val stopped: Boolean,
    val firstInstall: Long,
    val lastUpdate: Long,
    val sourceDir: String,
    val classification: Classification,
    /** Foreground time in the last 24 h (ms), -1 if usage access is not granted. */
    val foregroundMs24h: Long = -1,
    val lastTimeUsed: Long = 0,
    /** Total storage bytes (app + data + cache), -1 if unknown. */
    val storageBytes: Long = -1,
    /** Filled from `ps` when a shell executor is available; null = unknown. */
    val running: Boolean? = null,
    val rssKb: Long = 0,
    val installedForUser: Boolean = true
) {
    val isDisabled get() = !enabled
    val stateLabel: String
        get() = when {
            !installedForUser -> "Uninstalled (user 0)"
            !enabled -> "Disabled"
            suspended -> "Blocked (suspended)"
            running == true -> "Running"
            stopped -> "Stopped"
            else -> "Enabled"
        }
}

class AppDiscovery(private val context: Context) {
    private val pm = context.packageManager

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun installedPackages(): List<PackageInfo> {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        return if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getInstalledPackages(flags)
    }

    @Suppress("DEPRECATION")
    fun appInfo(pkg: String): ApplicationInfo? = runCatching {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        else pm.getApplicationInfo(pkg, flags)
    }.getOrNull()

    fun enabledSetting(pkg: String): Int =
        runCatching { pm.getApplicationEnabledSetting(pkg) }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

    fun loadIcon(pkg: String) = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()

    suspend fun discover(runningProcs: Map<String, Long>? = null): List<AppEntry> = withContext(Dispatchers.IO) {
        val usage = usageLast24h()
        val storage = if (hasUsageAccess()) context.getSystemService(StorageStatsManager::class.java) else null
        installedPackages().mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            // System apps removed for this user (pm uninstall --user 0) are kept, flagged installedForUser=false.
            toEntry(pi, ai, usage, storage, runningProcs)
        }.sortedBy { it.label.lowercase() }
    }

    suspend fun single(pkg: String, runningProcs: Map<String, Long>? = null): AppEntry? = withContext(Dispatchers.IO) {
        val pi = runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
            else pm.getPackageInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrNull() ?: return@withContext null
        val ai = pi.applicationInfo ?: return@withContext null
        val storage = if (hasUsageAccess()) context.getSystemService(StorageStatsManager::class.java) else null
        toEntry(pi, ai, usageLast24h(), storage, runningProcs)
    }

    private fun toEntry(
        pi: PackageInfo, ai: ApplicationInfo,
        usage: Map<String, Pair<Long, Long>>, storage: StorageStatsManager?, running: Map<String, Long>?
    ): AppEntry {
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val updated = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pi.packageName).installingPackageName
            else pm.getInstallerPackageName(pi.packageName)
        }.getOrNull()
        val installType = when {
            isSystem && updated -> "System (updated)"
            isSystem -> "Preinstalled system"
            installer == "com.android.vending" -> "Google Play"
            installer == "com.hihonor.appmarket" -> "HONOR App Market"
            installer == null -> "Sideloaded / unknown"
            else -> "Installed by $installer"
        }
        val setting = runCatching { pm.getApplicationEnabledSetting(pi.packageName) }.getOrDefault(0)
        val bytes = storage?.let {
            runCatching {
                val s = it.queryStatsForPackage(StorageManager.UUID_DEFAULT, pi.packageName, Process.myUserHandle())
                s.appBytes + s.dataBytes + s.cacheBytes
            }.getOrDefault(-1L)
        } ?: -1L
        val u = usage[pi.packageName]
        val rss = running?.get(pi.packageName)
        @Suppress("DEPRECATION")
        val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        return AppEntry(
            packageName = pi.packageName,
            label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pi.packageName),
            versionName = pi.versionName ?: "",
            versionCode = vc,
            isSystem = isSystem,
            isUpdatedSystem = updated,
            installType = installType,
            installer = installer,
            enabledSetting = setting,
            enabled = ai.enabled,
            suspended = (ai.flags and ApplicationInfo.FLAG_SUSPENDED) != 0,
            stopped = (ai.flags and ApplicationInfo.FLAG_STOPPED) != 0,
            firstInstall = pi.firstInstallTime,
            lastUpdate = pi.lastUpdateTime,
            sourceDir = ai.sourceDir ?: "",
            classification = SystemPackageClassifier.classify(pi.packageName, isSystem),
            foregroundMs24h = u?.first ?: if (usage.isEmpty()) -1L else 0L,
            lastTimeUsed = u?.second ?: 0,
            storageBytes = bytes,
            running = running?.let { rss != null },
            rssKb = rss ?: 0,
            installedForUser = (ai.flags and ApplicationInfo.FLAG_INSTALLED) != 0
        )
    }

    /** package -> (foreground ms, lastTimeUsed) for the past 24 h. Empty when usage access missing. */
    fun usageLast24h(): Map<String, Pair<Long, Long>> {
        if (!hasUsageAccess()) return emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = runCatching { usm.queryAndAggregateUsageStats(end - 86_400_000L, end) }.getOrNull() ?: return emptyMap()
        return stats.mapValues { it.value.totalTimeInForeground to it.value.lastTimeUsed }
    }
}
