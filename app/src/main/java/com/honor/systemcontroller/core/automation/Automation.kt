package com.honor.systemcontroller.core.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.honor.systemcontroller.HonorControllerApp
import com.honor.systemcontroller.R
import com.honor.systemcontroller.core.packages.AppAction
import com.honor.systemcontroller.core.packages.AppEntry
import com.honor.systemcontroller.data.BlockedApp
import java.util.concurrent.TimeUnit

/**
 * Blocking engine: applies the strongest supported restrictions to each blocklisted app,
 * records state, and verifies they are still in force.
 */
class BlocklistEngine(private val app: HonorControllerApp) {
    private val c get() = app.container

    data class Check(val app: BlockedApp, val violations: List<String>) { val ok get() = violations.isEmpty() }

    suspend fun add(entry: AppEntry, disable: Boolean, suspend: Boolean, restrictBg: Boolean, forceStop: Boolean): List<String> {
        val b = BlockedApp(entry.packageName, entry.label, disable, suspend, restrictBg, forceStop)
        c.db.blocklist().upsert(b)
        return apply(b, entry)
    }

    /** Applies every restriction of [b]. Returns one human-readable line per restriction. */
    suspend fun apply(b: BlockedApp, entryIn: AppEntry? = null): List<String> {
        val entry = entryIn ?: c.discovery.single(b.packageName) ?: return listOf("${b.label}: not installed")
        val out = mutableListOf<String>()
        suspend fun doAction(a: AppAction) {
            val g = c.controller.guard(entry, a)
            if (g != null) { out += "${a.title}: $g"; return }
            val r = c.controller.perform(entry, a)
            out += "${a.title}: ${if (r.success) "OK" else "FAILED"} – ${r.message.lineSequence().first()}"
        }
        if (b.restrictBackground) doAction(AppAction.RESTRICT_BACKGROUND)
        if (b.suspendLaunch) doAction(AppAction.BLOCK_LAUNCH)
        if (b.disablePackage) doAction(AppAction.DISABLE)
        if (b.forceStop && !b.disablePackage) doAction(AppAction.FORCE_STOP)
        val chk = check(b)
        c.db.blocklist().upsert(chk.app)
        return out
    }

    /** Verification uses only PackageManager/appops reads, so it also works without a shell for disable/suspend. */
    suspend fun check(b: BlockedApp): Check {
        val v = mutableListOf<String>()
        val installed = c.discovery.appInfo(b.packageName) != null
        if (!installed) v += "not installed"
        if (installed && b.disablePackage && !c.controller.isDisabled(b.packageName)) v += "re-enabled"
        if (installed && b.suspendLaunch && !c.controller.isSuspended(b.packageName)) v += "no longer suspended"
        if (installed && b.restrictBackground) {
            val shell = c.privilege.executor()
            if (shell != null) {
                val op = c.controller.readAppOp(shell, b.packageName, "RUN_ANY_IN_BACKGROUND")
                if (op != null && op != "ignore" && op != "deny") v += "background allowed again ($op)"
            }
        }
        val status = if (v.isEmpty()) "Restrictions active" else "Violation: " + v.joinToString(", ")
        val updated = b.copy(lastCheckedAt = System.currentTimeMillis(), lastStatus = status,
            violationCount = if (v.isEmpty()) b.violationCount else b.violationCount + 1)
        return Check(updated, v)
    }

    suspend fun checkAll(): List<Check> = c.db.blocklist().all().map { check(it).also { r -> c.db.blocklist().upsert(r.app) } }

    suspend fun remove(pkg: String, restore: Boolean): String {
        c.db.blocklist().delete(pkg)
        return if (restore) c.controller.restore(pkg).message else "Removed from blocklist (state left unchanged)"
    }

    /** Blocklisted apps that came to the foreground since the last check (needs usage access). */
    fun recentlyActive(pkgs: Set<String>, since: Long): Map<String, Long> {
        if (!c.discovery.hasUsageAccess() || pkgs.isEmpty()) return emptyMap()
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(since, System.currentTimeMillis())
        val e = UsageEvents.Event()
        val seen = mutableMapOf<String, Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName in pkgs && (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    e.eventType == UsageEvents.Event.FOREGROUND_SERVICE_START)) seen[e.packageName] = e.timeStamp
        }
        return seen
    }
}

/**
 * Periodic verification. Bounded: at most one pass per run, at most 50 apps, never loops,
 * never touches CRITICAL packages (guard() in PackageController).
 */
class BlocklistWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HonorControllerApp
        val s = app.container.settings
        val engine = app.container.blocklist
        val reapply = inputData.getBoolean(KEY_REAPPLY, false) || s.ruleReapplyOnViolation
        val messages = mutableListOf<String>()

        if (s.ruleVerifyBlocklist || reapply) {
            val checks = engine.checkAll().take(50)
            checks.filter { !it.ok }.forEach { chk ->
                messages += "${chk.app.label}: ${chk.violations.joinToString()}"
                if (reapply && app.container.privilege.executor() != null) engine.apply(chk.app)
            }
        }
        if (s.ruleNotifyActivity) {
            val since = if (s.lastActivityCheck == 0L) System.currentTimeMillis() - 3_600_000 else s.lastActivityCheck
            val list = app.container.db.blocklist().all()
            val active = engine.recentlyActive(list.map { it.packageName }.toSet(), since)
            active.forEach { (pkg, t) ->
                list.firstOrNull { it.packageName == pkg }?.let { b ->
                    app.container.db.blocklist().upsert(b.copy(lastSeenActiveAt = t))
                    messages += "${b.label} became active"
                }
            }
            s.lastActivityCheck = System.currentTimeMillis()
        }
        if (s.ruleHighUsageReport) {
            val limit = s.highUsageMinutes * 60_000L
            app.container.discovery.usageLast24h().filter { it.value.first > limit }.keys.take(5).forEach { pkg ->
                messages += "$pkg used > ${s.highUsageMinutes} min in 24 h"
            }
        }
        if (messages.isNotEmpty()) notify(applicationContext, "HONOR System Controller", messages.joinToString("\n"))
        return Result.success()
    }

    companion object {
        const val KEY_REAPPLY = "reapply"
        const val PERIODIC = "hsc_verify"
        const val ONESHOT = "hsc_verify_now"
        const val CHANNEL = "hsc_alerts"

        fun schedule(context: Context, intervalMin: Int) {
            val req = PeriodicWorkRequestBuilder<BlocklistWorker>(intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)

        fun runNow(context: Context, reapply: Boolean) {
            val req = OneTimeWorkRequestBuilder<BlocklistWorker>().setInputData(workDataOf(KEY_REAPPLY to reapply)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, req)
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Blocklist & automation alerts", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun notify(context: Context, title: String, text: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(1001, n)
        }
    }
}

/** After reboot: schedule verification and (optionally) a re-apply pass once Shizuku/root is back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? HonorControllerApp ?: return
        val s = app.container.settings
        if (s.ruleVerifyBlocklist) BlocklistWorker.schedule(context, s.verifyIntervalMin)
        if (s.ruleReapplyAfterBoot) {
            // Delay a few minutes so the user can restart Shizuku; worker re-applies only if a shell exists.
            val req = OneTimeWorkRequestBuilder<BlocklistWorker>()
                .setInitialDelay(5, TimeUnit.MINUTES)
                .setInputData(workDataOf(BlocklistWorker.KEY_REAPPLY to true)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("hsc_boot", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
