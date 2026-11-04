package com.honor.systemcontroller.core.packages

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import com.honor.systemcontroller.core.classify.Risk
import com.honor.systemcontroller.core.privilege.PrivilegeManager
import com.honor.systemcontroller.core.privilege.PrivilegeMode
import com.honor.systemcontroller.core.privilege.ShellExecutor
import com.honor.systemcontroller.core.privilege.ShellResult
import com.honor.systemcontroller.data.AppDatabase
import com.honor.systemcontroller.data.OperationLog
import com.honor.systemcontroller.data.PackageSnapshot
import kotlinx.coroutines.delay

enum class AppAction(val title: String, val needsShell: Boolean, val explanation: String) {
    FORCE_STOP("Force stop", true, "Kills all processes and marks the app stopped until it is opened again. Temporary: the app may start again when you launch it or another app binds to it."),
    DISABLE("Disable application", true, "Persistent. `pm disable-user` hides the app, removes its launcher icon and prevents it from running. Survives reboot. Data is kept."),
    ENABLE("Enable application", true, "Re-enables a disabled package (`pm enable`)."),
    BLOCK_LAUNCH("Block launching", true, "Persistent. `pm suspend` greys out the app icon; tapping it shows a system 'app paused' dialog and its activities/services cannot run."),
    UNBLOCK_LAUNCH("Unblock launching", true, "Reverses `pm suspend`."),
    RESTRICT_BACKGROUND("Restrict background", true, "Sets appops RUN_ANY_IN_BACKGROUND and RUN_IN_BACKGROUND to ignore and moves the app to the 'restricted' standby bucket. The app still works when you open it."),
    ALLOW_BACKGROUND("Allow background", true, "Restores default background permissions and the 'active' standby bucket."),
    UNINSTALL("Force delete (uninstall)", true, "User apps: `pm uninstall` removes the app AND its data permanently. System apps: `pm uninstall -k --user 0` removes it for your user only (the APK stays in the read-only system partition, so it can be brought back with Restore / `cmd package install-existing`)."),
    REINSTALL_SYSTEM("Re-install removed system app", true, "`cmd package install-existing` brings back a system app removed for user 0."),
    RESTORE("Restore original state", true, "Returns the package to the state recorded before this app first changed it.")
}

data class OpResult(val success: Boolean, val verified: Boolean, val message: String, val command: String = "")

/** Strict whitelist to make shell injection impossible. */
object Validators {
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$|^android$")
    private val CLS = Regex("^[A-Za-z0-9_.$]+$")
    fun pkg(p: String) { require(PKG.matches(p)) { "Invalid package name: $p" } }
    fun component(p: String, c: String) { pkg(p); require(CLS.matches(c)) { "Invalid component: $c" } }
}

/** Shell command templates shared by on-device execution and ADB script export. */
object Commands {
    fun forceStop(p: String) = "am force-stop --user 0 $p"
    fun disable(p: String) = "pm disable-user --user 0 $p"
    fun enable(p: String) = "pm enable --user 0 $p"
    fun suspend(p: String) = "pm suspend --user 0 $p"
    fun unsuspend(p: String) = "pm unsuspend --user 0 $p"
    fun restrictBg(p: String) = listOf(
        "cmd appops set $p RUN_ANY_IN_BACKGROUND ignore",
        "cmd appops set $p RUN_IN_BACKGROUND ignore",
        "am set-standby-bucket $p restricted"
    )
    fun allowBg(p: String, any: String? = null, run: String? = null, bucket: String? = null) = listOf(
        "cmd appops set $p RUN_ANY_IN_BACKGROUND ${any.toMode()}",
        "cmd appops set $p RUN_IN_BACKGROUND ${run.toMode()}",
        "am set-standby-bucket $p ${bucket.toBucket()}"
    )
    fun uninstallForUser(p: String) = "pm uninstall -k --user 0 $p"
    fun uninstallFull(p: String) = "pm uninstall $p"
    fun reinstall(p: String) = "cmd package install-existing --user 0 $p"
    fun disableComponent(p: String, c: String) = "pm disable --user 0 $p/$c"
    fun enableComponent(p: String, c: String) = "pm enable --user 0 $p/$c"

    private fun String?.toMode() = when (this) { "allow", "ignore", "deny", "default", "foreground" -> this; else -> "allow" }
    private fun String?.toBucket() = when (this) { "active", "working_set", "frequent", "rare", "restricted" -> this; else -> "active" }
}

class PackageController(
    private val context: Context,
    private val db: AppDatabase,
    private val privilege: PrivilegeManager,
    private val discovery: AppDiscovery
) {
    private val pm = context.packageManager

    // ---------- state reading (works without privileges) ----------
    fun describeState(pkg: String): String {
        val ai = discovery.appInfo(pkg) ?: return "not installed"
        val setting = discovery.enabledSetting(pkg)
        return buildString {
            append("enabled=").append(ai.enabled)
            append(", setting=").append(settingName(setting))
            append(", suspended=").append((ai.flags and ApplicationInfo.FLAG_SUSPENDED) != 0)
            append(", stopped=").append((ai.flags and ApplicationInfo.FLAG_STOPPED) != 0)
        }
    }

    /** True when the package exists for user 0 (FLAG_INSTALLED). */
    fun isInstalled(pkg: String): Boolean = isInstalledForUser(pkg)
    fun isDisabled(pkg: String): Boolean = discovery.appInfo(pkg)?.enabled == false
    fun isSuspended(pkg: String): Boolean = discovery.appInfo(pkg)?.let { (it.flags and ApplicationInfo.FLAG_SUSPENDED) != 0 } ?: false
    fun isStopped(pkg: String): Boolean = discovery.appInfo(pkg)?.let { (it.flags and ApplicationInfo.FLAG_STOPPED) != 0 } ?: false

    suspend fun readAppOp(shell: ShellExecutor, pkg: String, op: String): String? {
        val r = shell.run("cmd appops get $pkg $op")
        if (r.exitCode != 0) return null
        val text = r.stdout.lowercase()
        return listOf("ignore", "deny", "allow", "foreground", "default").firstOrNull { text.contains(": $it") || text.contains(" $it") }
            ?: if (text.contains("no operations")) "default" else null
    }

    suspend fun readBucket(shell: ShellExecutor, pkg: String): String? {
        val r = shell.run("am get-standby-bucket $pkg")
        if (r.exitCode != 0) return null
        return when (r.stdout.trim().lowercase()) {
            "5", "exempted" -> "active"
            "10", "active" -> "active"
            "20", "working_set" -> "working_set"
            "30", "frequent" -> "frequent"
            "40", "rare" -> "rare"
            "45", "restricted" -> "restricted"
            else -> null
        }
    }

    // ---------- backup ----------
    private suspend fun snapshotIfNeeded(shell: ShellExecutor, pkg: String) {
        if (db.snapshots().get(pkg) != null) return // keep the ORIGINAL state only
        val ai = discovery.appInfo(pkg) ?: return
        db.snapshots().insertIfAbsent(
            PackageSnapshot(
                packageName = pkg,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg),
                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                enabledSetting = discovery.enabledSetting(pkg),
                wasSuspended = (ai.flags and ApplicationInfo.FLAG_SUSPENDED) != 0,
                wasStopped = (ai.flags and ApplicationInfo.FLAG_STOPPED) != 0,
                runAnyInBackgroundMode = readAppOp(shell, pkg, "RUN_ANY_IN_BACKGROUND"),
                runInBackgroundMode = readAppOp(shell, pkg, "RUN_IN_BACKGROUND"),
                standbyBucket = readBucket(shell, pkg)
            )
        )
    }

    private suspend fun log(pkg: String, action: String, cmd: String, r: OpResult, before: String, after: String) {
        db.logs().insert(
            OperationLog(
                packageName = pkg, action = action, mode = privilege.mode.value.name, command = cmd,
                success = r.success, verified = r.verified, message = r.message, beforeState = before, afterState = after
            )
        )
    }

    /** Explains, per mode, whether the action can execute and how. */
    fun availability(action: AppAction): String {
        val ex = privilege.executor()
        return when {
            ex != null -> "Available (${ex.mode.title})"
            privilege.mode.value == PrivilegeMode.STANDARD -> "Needs Shizuku, ADB or root. In Standard mode you'll be sent to the system App info screen."
            privilege.mode.value == PrivilegeMode.ADB -> "Will copy the adb command. Start Shizuku to run it on the phone."
            else -> "Elevated mode selected but not connected."
        }
    }

    /** Guard rail: critical packages can never be disabled/blocked, whatever the mode. */
    fun guard(entry: AppEntry, action: AppAction): String? {
        val destructive = action == AppAction.DISABLE || action == AppAction.BLOCK_LAUNCH ||
            action == AppAction.RESTRICT_BACKGROUND || action == AppAction.UNINSTALL
        if (action == AppAction.UNINSTALL && entry.isSystem && entry.classification.risk != Risk.SAFE && entry.classification.risk != Risk.CAUTION)
            return "Blocked: ${entry.packageName} is ${entry.classification.risk.title.lowercase()}. Only system packages identified as non-essential or caution can be removed."
        if (!destructive) return null
        if (entry.packageName == context.packageName) return "Refusing to restrict HONOR System Controller itself."
        if (entry.packageName == "moe.shizuku.privileged.api") return "Refusing to restrict Shizuku (you would lose elevated access)."
        if (entry.classification.risk == Risk.CRITICAL && action != AppAction.RESTRICT_BACKGROUND)
            return "Blocked: ${entry.classification.purpose}. ${entry.classification.consequences}"
        if (entry.classification.risk == Risk.CRITICAL && action == AppAction.RESTRICT_BACKGROUND)
            return "Blocked: restricting background execution of a critical component can break ${entry.classification.purpose}."
        return null
    }

    suspend fun perform(entry: AppEntry, action: AppAction): OpResult {
        val pkg = entry.packageName
        runCatching { Validators.pkg(pkg) }.onFailure { return OpResult(false, false, it.message ?: "invalid") }
        guard(entry, action)?.let { msg ->
            val r = OpResult(false, false, msg)
            log(pkg, action.name, "-", r, describeState(pkg), describeState(pkg))
            return r
        }
        val shell = privilege.executor()
            ?: return OpResult(false, false, "No elevated executor connected. ${availability(action)}", commandsFor(pkg, action).joinToString("\n"))

        val before = describeState(pkg)
        if (action != AppAction.RESTORE && action != AppAction.ENABLE && action != AppAction.UNBLOCK_LAUNCH && action != AppAction.ALLOW_BACKGROUND && action != AppAction.REINSTALL_SYSTEM)
            snapshotIfNeeded(shell, pkg)

        if (action == AppAction.RESTORE) return restore(pkg)

        val cmds = commandsFor(pkg, action)
        val outputs = mutableListOf<ShellResult>()
        for (c in cmds) outputs += shell.run(c)
        delay(400)
        val (verified, vmsg) = verify(shell, pkg, action)
        val shellOk = outputs.all { it.exitCode == 0 && !it.combined.contains("Exception") && !it.combined.contains("Error:") }
        val msg = buildString {
            append(if (verified) "Confirmed by the system: " else "NOT confirmed: ")
            append(vmsg)
            val errs = outputs.filter { it.exitCode != 0 || it.combined.contains("Exception") || it.combined.contains("Error") }
            if (errs.isNotEmpty()) append("\nShell output: ").append(errs.joinToString(" | ") { it.combined.take(300) })
        }
        val r = OpResult(shellOk && verified, verified, msg, cmds.joinToString("\n"))
        log(pkg, action.name, r.command, r, before, describeState(pkg))
        return r
    }

    fun commandsFor(pkg: String, action: AppAction): List<String> = when (action) {
        AppAction.FORCE_STOP -> listOf(Commands.forceStop(pkg))
        AppAction.DISABLE -> listOf(Commands.disable(pkg))
        AppAction.ENABLE -> listOf(Commands.enable(pkg))
        AppAction.BLOCK_LAUNCH -> listOf(Commands.suspend(pkg))
        AppAction.UNBLOCK_LAUNCH -> listOf(Commands.unsuspend(pkg))
        AppAction.RESTRICT_BACKGROUND -> Commands.restrictBg(pkg)
        AppAction.ALLOW_BACKGROUND -> Commands.allowBg(pkg)
        AppAction.UNINSTALL -> listOf(if (discovery.appInfo(pkg)?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } == true) Commands.uninstallForUser(pkg) else Commands.uninstallFull(pkg))
        AppAction.REINSTALL_SYSTEM -> listOf(Commands.reinstall(pkg))
        AppAction.RESTORE -> listOf(Commands.reinstall(pkg), Commands.enable(pkg), Commands.unsuspend(pkg)) + Commands.allowBg(pkg)
    }

    /** True when the package is installed for user 0. */
    fun isInstalledForUser(pkg: String): Boolean = discovery.appInfo(pkg)?.let { (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0 } ?: false

    /** Re-reads real system state. An operation is only reported successful if this agrees. */
    private suspend fun verify(shell: ShellExecutor, pkg: String, action: AppAction): Pair<Boolean, String> = when (action) {
        AppAction.FORCE_STOP -> {
            // FLAG_STOPPED is set by force-stop. Some always-on apps restart instantly; report honestly.
            val stopped = isStopped(pkg)
            val stillRunning = shell.run("pidof $pkg").stdout.isNotBlank()
            when {
                stopped && !stillRunning -> true to "package is in stopped state, no live process"
                !stillRunning -> true to "no live process (stopped flag not reported)"
                else -> false to "process restarted immediately (it is bound by another app or a system service)"
            }
        }
        AppAction.DISABLE -> isDisabled(pkg).let { it to if (it) "package disabled for user 0" else "package still enabled" }
        AppAction.ENABLE -> (!isDisabled(pkg)).let { it to if (it) "package enabled" else "package still disabled" }
        AppAction.BLOCK_LAUNCH -> isSuspended(pkg).let { it to if (it) "package suspended (launch blocked)" else "package not suspended" }
        AppAction.UNBLOCK_LAUNCH -> (!isSuspended(pkg)).let { it to if (it) "package unsuspended" else "package still suspended" }
        AppAction.RESTRICT_BACKGROUND -> {
            val op = readAppOp(shell, pkg, "RUN_ANY_IN_BACKGROUND")
            val bucket = readBucket(shell, pkg)
            (op == "ignore" || op == "deny") to "RUN_ANY_IN_BACKGROUND=${op ?: "unknown"}, bucket=${bucket ?: "unknown"}"
        }
        AppAction.ALLOW_BACKGROUND -> {
            val op = readAppOp(shell, pkg, "RUN_ANY_IN_BACKGROUND")
            (op != "ignore" && op != "deny" && op != null) to "RUN_ANY_IN_BACKGROUND=${op ?: "unknown"}"
        }
        AppAction.UNINSTALL -> (!isInstalledForUser(pkg)).let { it to if (it) "package removed for user 0" else "package is still installed" }
        AppAction.REINSTALL_SYSTEM -> isInstalledForUser(pkg).let { it to if (it) "package installed again" else "package still not installed" }
        AppAction.RESTORE -> true to "restored"
    }

    /** Restore to the recorded ORIGINAL state; falls back to a safe default when no snapshot exists. */
    suspend fun restore(pkg: String): OpResult {
        Validators.pkg(pkg)
        val shell = privilege.executor() ?: return OpResult(false, false, "No elevated executor connected – export the ADB restore script instead.")
        val snap = db.snapshots().get(pkg)
        val before = describeState(pkg)
        val cmds = mutableListOf<String>()
        if (snap?.rootRemovedDir != null)
            return OpResult(false, false, "${snap.label} was removed from the system partition with root. Use 'Undo root removal' in Backup & Recovery, reboot, then Restore.")
        if (!isInstalledForUser(pkg)) {
            if (snap != null && !snap.isSystem)
                return OpResult(false, false, "${snap.label} was a user app that was fully uninstalled. Its APK and data no longer exist on the phone – reinstall it from the store.")
            cmds += Commands.reinstall(pkg)
        }
        val wantDisabled = snap != null && (snap.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || snap.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        if (!wantDisabled) cmds += Commands.enable(pkg)
        if (snap == null || !snap.wasSuspended) cmds += Commands.unsuspend(pkg)
        cmds += Commands.allowBg(pkg, snap?.runAnyInBackgroundMode, snap?.runInBackgroundMode, snap?.standbyBucket)
        snap?.disabledComponents?.split('|')?.filter { it.isNotBlank() }?.forEach { c ->
            if (runCatching { Validators.component(pkg, c) }.isSuccess) cmds += Commands.enableComponent(pkg, c)
        }
        val outs = cmds.map { shell.run(it) }
        delay(400)
        val ok = isInstalled(pkg) && isDisabled(pkg) == wantDisabled && (snap?.wasSuspended ?: false) == isSuspended(pkg)
        val r = OpResult(ok, ok,
            if (ok) "Restored to original state (${snap?.let { "snapshot from " + java.text.DateFormat.getDateTimeInstance().format(it.capturedAt) } ?: "no snapshot – defaults"})"
            else "Restore incomplete. Output: " + outs.filter { it.exitCode != 0 }.joinToString(" | ") { it.combined.take(200) },
            cmds.joinToString("\n"))
        log(pkg, AppAction.RESTORE.name, r.command, r, before, describeState(pkg))
        if (ok) db.snapshots().delete(pkg)
        return r
    }

    // ---------- components ----------
    data class ComponentItem(val className: String, val type: String, val enabled: Boolean)

    fun components(pkg: String): List<ComponentItem> {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS
        @Suppress("DEPRECATION")
        val pi = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else pm.getPackageInfo(pkg, flags)
        }.getOrNull() ?: return emptyList()
        fun state(cls: String, defaultEnabled: Boolean): Boolean {
            val s = runCatching { pm.getComponentEnabledSetting(ComponentName(pkg, cls)) }.getOrDefault(0)
            return when (s) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> defaultEnabled
                else -> false
            }
        }
        val out = mutableListOf<ComponentItem>()
        pi.services?.forEach { out += ComponentItem(it.name, "Service", state(it.name, it.enabled)) }
        pi.receivers?.forEach { out += ComponentItem(it.name, "Receiver", state(it.name, it.enabled)) }
        pi.activities?.forEach { out += ComponentItem(it.name, "Activity", state(it.name, it.enabled)) }
        pi.providers?.forEach { out += ComponentItem(it.name, "Provider", state(it.name, it.enabled)) }
        return out
    }

    suspend fun setComponent(entry: AppEntry, cls: String, enable: Boolean): OpResult {
        val pkg = entry.packageName
        runCatching { Validators.component(pkg, cls) }.onFailure { return OpResult(false, false, it.message ?: "invalid") }
        if (!enable && entry.classification.risk == Risk.CRITICAL)
            return OpResult(false, false, "Blocked: components of critical packages are protected.")
        val shell = privilege.executor() ?: return OpResult(false, false, "Needs Shizuku or root.",
            if (enable) Commands.enableComponent(pkg, cls) else Commands.disableComponent(pkg, cls))
        if (!enable) snapshotIfNeeded(shell, pkg)
        val cmd = if (enable) Commands.enableComponent(pkg, cls) else Commands.disableComponent(pkg, cls)
        val before = "component $cls"
        val out = shell.run(cmd)
        val s = runCatching { pm.getComponentEnabledSetting(ComponentName(pkg, cls)) }.getOrDefault(-1)
        val verified = if (enable) s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        else s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        if (verified && !enable) {
            db.snapshots().get(pkg)?.let { snap ->
                val set = snap.disabledComponents.split('|').filter { it.isNotBlank() }.toMutableSet().apply { add(cls) }
                db.snapshots().upsert(snap.copy(disabledComponents = set.joinToString("|")))
            }
        }
        val r = OpResult(verified, verified, if (verified) "Component state confirmed (${settingName(s)})" else "Not confirmed: ${out.combined.take(300)}", cmd)
        log(pkg, if (enable) "ENABLE_COMPONENT" else "DISABLE_COMPONENT", cmd, r, before, "setting=${settingName(s)}")
        return r
    }

    // ---------- Standard-mode fallbacks ----------
    fun openAppInfo(pkg: String) {
        context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Standard mode uninstall: the system's own uninstall confirmation (user apps only). */
    fun openUninstallDialog(pkg: String) {
        @Suppress("DEPRECATION")
        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun logStandardRedirect(pkg: String, action: AppAction) {
        log(pkg, action.name, "open App info", OpResult(false, false, "Standard mode: opened system App info for manual action"), describeState(pkg), describeState(pkg))
    }

    companion object {
        fun settingName(s: Int) = when (s) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "default"
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enabled"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disabled"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disabled-user"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "disabled-until-used"
            else -> "unknown($s)"
        }
    }
}
