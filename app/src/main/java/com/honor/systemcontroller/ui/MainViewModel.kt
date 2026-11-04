package com.honor.systemcontroller.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.honor.systemcontroller.HonorControllerApp
import com.honor.systemcontroller.core.automation.BlocklistWorker
import com.honor.systemcontroller.core.packages.AppAction
import com.honor.systemcontroller.core.packages.AppEntry
import com.honor.systemcontroller.core.packages.OpResult
import com.honor.systemcontroller.core.privilege.PrivilegeMode
import com.honor.systemcontroller.core.privilege.ShizukuState
import com.honor.systemcontroller.core.process.MemorySummary
import com.honor.systemcontroller.core.process.ProcInfo
import com.honor.systemcontroller.data.BlockedApp
import com.honor.systemcontroller.data.OperationLog
import com.honor.systemcontroller.data.PackageSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppFilter(val title: String) {
    ALL("All"), RUNNING("Running"), BACKGROUND("Background"), SYSTEM("System"), USER("User"),
    DISABLED("Disabled"), REMOVED("Uninstalled"), RECENT("Recently installed"), HIGH("High resource")
}

enum class ExportKind(val fileName: String, val mime: String) {
    JSON("honor_controller_backup.json", "application/json"),
    SH("restore_honor_controller.sh", "text/x-sh"),
    BAT("restore_honor_controller.bat", "text/plain"),
    EMERGENCY("emergency_enable_all.sh", "text/x-sh")
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HonorControllerApp
    val c = app.container

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _procs = MutableStateFlow<List<ProcInfo>?>(null)
    val procs: StateFlow<List<ProcInfo>?> = _procs.asStateFlow()
    private val _memory = MutableStateFlow(c.processes.memory())
    val memory: StateFlow<MemorySummary> = _memory.asStateFlow()
    private val _memHistory = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()
    private val _usageAccess = MutableStateFlow(c.discovery.hasUsageAccess())
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()
    private val _theme = MutableStateFlow(c.settings.theme)
    val theme: StateFlow<String> = _theme.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val mode = c.privilege.mode
    val rootVerified = c.privilege.rootVerified
    val shizukuStatus = ShizukuState.status
    val shizukuReady = ShizukuState.ready

    val logs: StateFlow<List<OperationLog>> = c.db.logs().observeRecent().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val snapshots: StateFlow<List<PackageSnapshot>> = c.db.snapshots().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val blocklist: StateFlow<List<BlockedApp>> = c.db.blocklist().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init { refresh() }

    fun onResume() {
        _usageAccess.value = c.discovery.hasUsageAccess()
        _memory.value = c.processes.memory()
        refresh()
    }

    private fun toast(s: String) { _messages.tryEmit(s) }

    fun refresh() = viewModelScope.launch {
        _loading.value = true
        try {
            val running = runCatching { c.processes.runningPackages() }.getOrNull()
            _apps.value = c.discovery.discover(running)
            _memory.value = c.processes.memory()
            _memHistory.value = (_memHistory.value + _memory.value.usedFraction).takeLast(30)
        } catch (t: Throwable) {
            toast("Failed to load applications: ${t.message}")
        } finally { _loading.value = false }
    }

    fun refreshProcesses() = viewModelScope.launch {
        _procs.value = runCatching { c.processes.processes() }.getOrNull()
        _memory.value = c.processes.memory()
        _memHistory.value = (_memHistory.value + _memory.value.usedFraction).takeLast(30)
    }

    fun filtered(list: List<AppEntry>, filter: AppFilter, query: String): List<AppEntry> {
        val q = query.trim().lowercase()
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        return list.filter { a ->
            val matches = q.isEmpty() || a.label.lowercase().contains(q) || a.packageName.lowercase().contains(q)
            matches && (a.installedForUser || filter == AppFilter.REMOVED) && when (filter) {
                AppFilter.ALL -> true
                AppFilter.REMOVED -> !a.installedForUser
                AppFilter.RUNNING -> a.running == true
                AppFilter.BACKGROUND -> a.running == true && a.foregroundMs24h <= 0
                AppFilter.SYSTEM -> a.isSystem
                AppFilter.USER -> !a.isSystem
                AppFilter.DISABLED -> a.isDisabled || a.suspended
                AppFilter.RECENT -> a.firstInstall >= weekAgo || a.lastUpdate >= weekAgo
                AppFilter.HIGH -> a.rssKb > 300_000 || a.foregroundMs24h > 3_600_000 || a.storageBytes > 2_000_000_000L
            }
        }.let { if (filter == AppFilter.HIGH) it.sortedByDescending { e -> e.rssKb } else it }
    }

    fun findApp(pkg: String): AppEntry? = _apps.value.firstOrNull { it.packageName == pkg }

    fun perform(entry: AppEntry, action: AppAction, onDone: (OpResult) -> Unit = {}) = viewModelScope.launch {
        if (c.privilege.executor() == null) {
            val cmds = c.controller.commandsFor(entry.packageName, action)
            if (mode.value == PrivilegeMode.ADB) {
                copy(cmds.joinToString("\n") { "adb shell $it" })
                toast("ADB command copied to clipboard – run it on your computer")
            } else if (action == AppAction.UNINSTALL && !entry.isSystem) {
                c.controller.logStandardRedirect(entry.packageName, action)
                c.controller.openUninstallDialog(entry.packageName)
                toast("Confirm in the system uninstall dialog.")
            } else if (action == AppAction.UNINSTALL && !entry.isSystem) {
                c.controller.logStandardRedirect(entry.packageName, action)
                c.controller.openUninstallDialog(entry.packageName)
                toast("Standard mode: confirm in Android's uninstall dialog.")
            } else {
                c.controller.logStandardRedirect(entry.packageName, action)
                c.controller.openAppInfo(entry.packageName)
                toast("Standard mode: use the system App info screen (Force stop / Disable / Battery).")
            }
            onDone(OpResult(false, false, "Not executed in ${mode.value.title} mode", cmds.joinToString("\n")))
            return@launch
        }
        _busy.value = "${action.title}: ${entry.label}"
        val r = try { c.controller.perform(entry, action) } finally { _busy.value = null }
        toast(r.message.lineSequence().first())
        onDone(r)
        if (action == AppAction.UNINSTALL && r.success) _apps.value = _apps.value.filter { it.packageName != entry.packageName }
        else refreshOne(entry.packageName)
    }

    fun rootRemove(entry: AppEntry) = viewModelScope.launch {
        _busy.value = "Removing ${entry.label} from system partition"
        val r = try { c.rootDebloat.remove(entry) } finally { _busy.value = null }
        toast(r.message); refreshOne(entry.packageName)
    }
    fun rootUndo(pkg: String) = viewModelScope.launch { toast(c.rootDebloat.undo(pkg).message) }
    fun rebootDevice() = viewModelScope.launch { c.rootDebloat.reboot() }
    fun uninstallViaSystemDialog(entry: AppEntry) { c.controller.openUninstallDialog(entry.packageName) }

    fun setComponent(entry: AppEntry, cls: String, enable: Boolean, onDone: (OpResult) -> Unit) = viewModelScope.launch {
        val r = c.controller.setComponent(entry, cls, enable)
        toast(r.message); onDone(r)
    }

    private suspend fun refreshOne(pkg: String) {
        val running = runCatching { c.processes.runningPackages() }.getOrNull()
        val e = c.discovery.single(pkg, running) ?: return
        _apps.value = _apps.value.map { if (it.packageName == pkg) e else it }
    }

    fun copy(text: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("adb", text))
    }

    // ----- privilege -----
    fun setMode(m: PrivilegeMode) { c.privilege.setMode(m); ShizukuState.refresh(); refresh() }
    fun requestShizuku() { ShizukuState.requestPermission() }
    fun verifyRoot() = viewModelScope.launch {
        val r = c.privilege.verifyRoot()
        toast(if (c.privilege.rootVerified.value) "Root verified" else "Root not available: ${r.combined.take(120)}")
        refresh()
    }

    // ----- blocklist -----
    fun addToBlocklist(entry: AppEntry, disable: Boolean, suspend: Boolean, restrictBg: Boolean, forceStop: Boolean) = viewModelScope.launch {
        _busy.value = "Blocking ${entry.label}"
        val lines = try { c.blocklist.add(entry, disable, suspend, restrictBg, forceStop) } finally { _busy.value = null }
        toast(if (c.privilege.executor() == null) "Added. Connect Shizuku/root to apply restrictions." else lines.joinToString("\n"))
        refreshOne(entry.packageName)
    }
    fun reapply(b: BlockedApp) = viewModelScope.launch {
        _busy.value = "Re-applying ${b.label}"
        val lines = try { c.blocklist.apply(b) } finally { _busy.value = null }
        toast(lines.joinToString("\n")); refreshOne(b.packageName)
    }
    fun removeFromBlocklist(pkg: String, restore: Boolean) = viewModelScope.launch {
        toast(c.blocklist.remove(pkg, restore)); refreshOne(pkg)
    }
    fun checkBlocklist() = viewModelScope.launch {
        val checks = c.blocklist.checkAll()
        val bad = checks.count { !it.ok }
        toast(if (bad == 0) "All ${checks.size} restrictions active" else "$bad app(s) no longer restricted")
    }
    fun stopBlocklistedNow() = viewModelScope.launch {
        val ex = c.privilege.executor() ?: run { toast("Needs Shizuku or root"); return@launch }
        var n = 0
        blocklist.value.forEach { b ->
            val e = c.discovery.single(b.packageName) ?: return@forEach
            if (c.controller.perform(e, AppAction.FORCE_STOP).success) n++
        }
        toast("Force-stopped $n of ${blocklist.value.size} (${ex.mode.title})")
    }

    // ----- backup & recovery -----
    fun restore(pkg: String) = viewModelScope.launch {
        _busy.value = "Restoring $pkg"
        val r = try { c.controller.restore(pkg) } finally { _busy.value = null }
        toast(r.message); refreshOne(pkg)
    }
    fun restoreMany(pkgs: Collection<String>) = viewModelScope.launch {
        var ok = 0
        pkgs.forEachIndexed { i, p ->
            _busy.value = "Restoring ${i + 1}/${pkgs.size}"
            if (c.controller.restore(p).success) ok++
        }
        _busy.value = null
        toast("Restored $ok of ${pkgs.size}"); refresh()
    }
    fun export(kind: ExportKind, uri: Uri) = viewModelScope.launch {
        val text = when (kind) {
            ExportKind.JSON -> c.recovery.json()
            ExportKind.SH -> c.recovery.shellScript()
            ExportKind.BAT -> c.recovery.windowsBatch()
            ExportKind.EMERGENCY -> c.recovery.emergencyEnableAll()
        }
        toast(if (c.recovery.writeTo(uri, text)) "Exported ${kind.fileName}" else "Export failed")
    }
    fun copyRestoreScript() = viewModelScope.launch { copy(c.recovery.shellScript()); toast("Restore script copied") }
    fun clearLog() = viewModelScope.launch { c.db.logs().clear() }

    // ----- automation -----
    fun applySchedule() {
        val s = c.settings
        if (s.ruleVerifyBlocklist) BlocklistWorker.schedule(app, s.verifyIntervalMin) else BlocklistWorker.cancel(app)
    }
    fun runAutomationNow(reapply: Boolean) { BlocklistWorker.runNow(app, reapply); toast("Automation pass queued") }

    fun setTheme(t: String) { c.settings.theme = t; _theme.value = t }

    // ----- ADB / shell console (allowlisted commands only) -----
    private val consoleAllow = listOf(
        Regex("^am force-stop( --user 0)? [A-Za-z0-9_.]+$"),
        Regex("^pm (disable-user|enable|suspend|unsuspend)( --user 0)? [A-Za-z0-9_.]+(/[A-Za-z0-9_.$]+)?$"),
        Regex("^pm list packages( -[sde3])*( [A-Za-z0-9_.]+)?$"),
        Regex("^pm path [A-Za-z0-9_.]+$"),
        Regex("^pm uninstall( -k)?( --user 0)? [A-Za-z0-9_.]+$"),
        Regex("^cmd package install-existing( --user 0)? [A-Za-z0-9_.]+$"),
        Regex("^cmd appops (get|set) [A-Za-z0-9_.]+( [A-Z_]+)?( (allow|ignore|deny|default|foreground))?$"),
        Regex("^am (get|set)-standby-bucket [A-Za-z0-9_.]+( (active|working_set|frequent|rare|restricted))?$"),
        Regex("^dumpsys package [A-Za-z0-9_.]+$"),
        Regex("^ps -A( -o [A-Z%,]+)?$"),
        Regex("^pidof [A-Za-z0-9_.:]+$"),
        Regex("^getprop( [a-z0-9_.]+)?$"),
        Regex("^id$")
    )

    fun runConsole(cmdIn: String) = viewModelScope.launch {
        val cmd = cmdIn.trim().removePrefix("adb shell ").trim()
        if (consoleAllow.none { it.matches(cmd) }) {
            _console.value = _console.value + "$ $cmd" + "Rejected: only supported package-management commands are allowed."
            return@launch
        }
        // Protect critical packages from console disables too.
        val target = cmd.split(' ').lastOrNull()?.substringBefore('/')
        if ((cmd.startsWith("pm disable-user") || cmd.startsWith("pm suspend") || cmd.startsWith("pm uninstall")) && target != null) {
            val e = findApp(target) ?: c.discovery.single(target)
            val act = when {
                cmd.startsWith("pm suspend") -> AppAction.BLOCK_LAUNCH
                cmd.startsWith("pm uninstall") -> AppAction.UNINSTALL
                else -> AppAction.DISABLE
            }
            val g = e?.let { c.controller.guard(it, act) }
            if (g != null) { _console.value = _console.value + "$ $cmd" + g; return@launch }
        }
        val ex = c.privilege.executor()
        if (ex == null) {
            copy("adb shell $cmd")
            _console.value = _console.value + "$ $cmd" + "No on-device shell. Copied 'adb shell $cmd' to clipboard."
            return@launch
        }
        val r = ex.run(cmd, 30)
        _console.value = (_console.value + "$ $cmd" + (r.combined.ifBlank { "(exit ${r.exitCode})" }).take(6000)).takeLast(200)
        c.db.logs().insert(OperationLog(packageName = target ?: "-", action = "CONSOLE", mode = ex.mode.name, command = cmd,
            success = r.exitCode == 0, verified = false, message = r.combined.take(500), beforeState = "", afterState = ""))
    }
    fun clearConsole() { _console.value = emptyList() }
}
