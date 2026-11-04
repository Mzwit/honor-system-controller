package com.honor.systemcontroller.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honor.systemcontroller.ui.ExportKind
import com.honor.systemcontroller.ui.MainViewModel
import com.honor.systemcontroller.ui.components.AppIcon
import com.honor.systemcontroller.ui.components.ConfirmDialog
import com.honor.systemcontroller.ui.components.LineGraph
import com.honor.systemcontroller.ui.components.Mono
import com.honor.systemcontroller.ui.components.SectionCard
import com.honor.systemcontroller.ui.components.StatusChip
import com.honor.systemcontroller.ui.components.UsageBar
import com.honor.systemcontroller.ui.theme.Danger
import com.honor.systemcontroller.ui.theme.Info
import com.honor.systemcontroller.ui.theme.Ok
import com.honor.systemcontroller.ui.theme.Warn
import java.text.DateFormat

@Composable
fun BlocklistScreen(vm: MainViewModel, open: (String) -> Unit) {
    val list by vm.blocklist.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<String?>(null) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.checkBlocklist() }) { Text("Verify all") }
            OutlinedButton({ vm.runAutomationNow(true) }) { Text("Re-apply") }
            OutlinedButton({ vm.stopBlocklistedNow() }) { Text("Stop all") }
        }
        if (list.isEmpty()) Text("No blocked apps. Open any app and choose “Add to blocklist…”.", Modifier.padding(16.dp))
        LazyColumn {
            items(list, key = { it.packageName }) { b ->
                Column(Modifier.fillMaxWidth().clickable { open(b.packageName) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(b.packageName)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.label, style = MaterialTheme.typography.titleMedium)
                            Text(b.packageName, style = MaterialTheme.typography.labelSmall)
                        }
                        StatusChip(if (b.lastStatus.startsWith("Violation")) "Violation" else if (b.lastCheckedAt == 0L) "Unchecked" else "Active",
                            if (b.lastStatus.startsWith("Violation")) Danger else if (b.lastCheckedAt == 0L) Warn else Ok)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        if (b.restrictBackground) StatusChip("Background blocked", Info)
                        if (b.suspendLaunch) StatusChip("Launch blocked", Info)
                        if (b.disablePackage) StatusChip("Disabled", Info)
                        if (b.forceStop) StatusChip("Force-stop", Info)
                    }
                    Text(b.lastStatus + (if (b.lastCheckedAt > 0) " · checked ${df.format(b.lastCheckedAt)}" else "") +
                        (if (b.lastSeenActiveAt > 0) " · last active ${df.format(b.lastSeenActiveAt)}" else "") +
                        (if (b.violationCount > 0) " · ${b.violationCount} violation(s)" else ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton({ vm.reapply(b) }) { Text("Re-apply") }
                        TextButton({ removing = b.packageName }) { Text("Remove", color = Danger) }
                    }
                }
                HorizontalDivider()
            }
        }
    }
    removing?.let { pkg ->
        ConfirmDialog("Remove from blocklist", "Also restore the app to its original state (enable, unsuspend, allow background)?",
            "Remove & restore", onConfirm = { vm.removeFromBlocklist(pkg, true) }, onDismiss = { removing = null })
    }
}

@Composable
fun ProcessesScreen(vm: MainViewModel, open: (String) -> Unit) {
    val procs by vm.procs.collectAsStateWithLifecycle()
    val mem by vm.memory.collectAsStateWithLifecycle()
    val hist by vm.memHistory.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    var appsOnly by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) { vm.refreshProcesses(); kotlinx.coroutines.delay(5000) }
    }
    val pkgs = remember(apps) { apps.map { it.packageName }.toSet() }
    val shown = (procs ?: emptyList()).filter { !appsOnly || it.packageName in pkgs }.sortedByDescending { it.rssKb }
    val maxRss = (shown.maxOfOrNull { it.rssKb } ?: 1L).coerceAtLeast(1L)
    Column(Modifier.fillMaxSize()) {
        SectionCard("Memory", Modifier.padding(16.dp)) {
            Text("${mem.totalMb - mem.availMb} / ${mem.totalMb} MB used · threshold ${mem.thresholdMb} MB")
            UsageBar(mem.usedFraction, if (mem.usedFraction > 0.85f) Danger else MaterialTheme.colorScheme.primary)
            LineGraph(hist, MaterialTheme.colorScheme.secondary)
        }
        if (procs == null) {
            Text("Process list unavailable: Android 7+ hides other apps' processes. Connect Shizuku or root in Settings to see and stop them.",
                Modifier.padding(16.dp), color = Warn)
            return@Column
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(appsOnly, { appsOnly = !appsOnly }, { Text("Apps only") })
            Spacer(Modifier.width(8.dp))
            Text("${shown.size} processes · refreshes every 5 s", style = MaterialTheme.typography.labelSmall)
        }
        LazyColumn {
            items(shown, key = { it.pid }) { p ->
                Column(Modifier.fillMaxWidth().clickable(enabled = p.packageName in pkgs) { open(p.packageName) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row {
                        Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text("${p.rssKb / 1024} MB · ${"%.1f".format(p.cpu)}%", style = MaterialTheme.typography.labelSmall)
                    }
                    UsageBar(p.rssKb.toFloat() / maxRss, if (p.cpu > 20) Danger else Info)
                    Text("pid ${p.pid} · ${p.user}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun AutomationScreen(vm: MainViewModel) {
    val s = vm.c.settings
    var verify by remember { mutableStateOf(s.ruleVerifyBlocklist) }
    var reapply by remember { mutableStateOf(s.ruleReapplyOnViolation) }
    var boot by remember { mutableStateOf(s.ruleReapplyAfterBoot) }
    var notify by remember { mutableStateOf(s.ruleNotifyActivity) }
    var high by remember { mutableStateOf(s.ruleHighUsageReport) }
    var interval by remember { mutableStateOf(s.verifyIntervalMin.toFloat()) }
    var highMin by remember { mutableStateOf(s.highUsageMinutes.toFloat()) }

    @Composable
    fun Rule(title: String, desc: String, v: Boolean, set: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(v, { set(it); vm.applySchedule() })
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Rules") {
            Rule("Verify blocklist periodically", "Checks that disabled/suspended/restricted apps stay that way (WorkManager).", verify) { verify = it; s.ruleVerifyBlocklist = it }
            Rule("Re-apply when violated", "Needs Shizuku/root at that moment. Bounded: one pass per run, no loops.", reapply) { reapply = it; s.ruleReapplyOnViolation = it }
            Rule("Re-apply after reboot", "Runs 5 minutes after boot so you can restart Shizuku first.", boot) { boot = it; s.ruleReapplyAfterBoot = it }
            Rule("Notify when blocked apps become active", "Uses usage events (usage access required).", notify) { notify = it; s.ruleNotifyActivity = it }
            Rule("High usage report", "Notifies when any app exceeds the foreground limit below.", high) { high = it; s.ruleHighUsageReport = it }
        }
        SectionCard("Schedule") {
            Text("Verification interval: ${interval.toInt()} min (Android minimum 15)")
            Slider(interval, { interval = it }, valueRange = 15f..360f, onValueChangeFinished = { s.verifyIntervalMin = interval.toInt(); vm.applySchedule() })
            Text("High usage threshold: ${highMin.toInt()} min / 24 h")
            Slider(highMin, { highMin = it }, valueRange = 30f..600f, onValueChangeFinished = { s.highUsageMinutes = highMin.toInt() })
        }
        SectionCard("Run now") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.runAutomationNow(false) }) { Text("Check now") }
                OutlinedButton({ vm.runAutomationNow(true) }) { Text("Check & re-apply") }
                OutlinedButton({ vm.stopBlocklistedNow() }) { Text("Stop selected") }
            }
            Text("The engine never force-stops in a loop and never touches packages classified as critical.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun BackupScreen(vm: MainViewModel) {
    val snaps by vm.snapshots.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val selected = remember { mutableStateListOf<String>() }
    var confirmAll by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportKind?>(null) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val k = pendingExport
        if (uri != null && k != null) vm.export(k, uri)
        pendingExport = null
    }
    fun export(k: ExportKind) { pendingExport = k; exporter.launch(k.fileName) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionCard("Export recovery information", Modifier.padding(top = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ export(ExportKind.SH) }) { Text("ADB script (.sh)") }
                    OutlinedButton({ export(ExportKind.BAT) }) { Text("Windows (.bat)") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ export(ExportKind.JSON) }) { Text("Full backup (JSON)") }
                    OutlinedButton({ export(ExportKind.EMERGENCY) }) { Text("Emergency enable-all") }
                }
                TextButton({ vm.copyRestoreScript() }) { Text("Copy restore script to clipboard") }
            }
        }
        item {
            SectionCard("Modified packages (${snaps.size})") {
                if (snaps.isEmpty()) Text("Nothing has been changed by this app.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.restoreMany(selected.toList()); selected.clear() }, enabled = selected.isNotEmpty()) { Text("Restore selected (${selected.size})") }
                    OutlinedButton({ confirmAll = true }, enabled = snaps.isNotEmpty()) { Text("Restore all") }
                }
            }
        }
        items(snaps, key = { it.packageName }) { s ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(s.packageName in selected, { if (it) selected.add(s.packageName) else selected.remove(s.packageName) })
                    AppIcon(s.packageName, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = MaterialTheme.typography.titleMedium)
                        Text("${s.packageName} · saved ${df.format(s.capturedAt)}", style = MaterialTheme.typography.labelSmall)
                        if (s.rootRemovedDir != null) StatusChip("Root-removed: ${s.rootRemovedDir}", Danger)
                    }
                    TextButton({ vm.restore(s.packageName) }) { Text("Restore") }
                }
                if (s.rootRemovedDir != null) Row {
                    TextButton({ vm.rootUndo(s.packageName) }) { Text("Undo root removal") }
                    TextButton({ vm.rebootDevice() }) { Text("Reboot now", color = Danger) }
                }
                HorizontalDivider()
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Audit log (${logs.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton({ vm.clearLog() }) { Text("Clear") }
            }
        }
        items(logs, key = { "log${it.id}" }) { l ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Row {
                    StatusChip(if (l.success) "OK" else if (l.verified) "Partial" else "Failed", if (l.success) Ok else Danger)
                    Spacer(Modifier.width(6.dp))
                    Text("${l.action} · ${l.packageName}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${df.format(l.timestamp)} · ${l.mode}", style = MaterialTheme.typography.labelSmall)
                Text(l.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (l.command.isNotBlank() && l.command != "-") Mono(l.command.take(300))
            }
        }
        item { Spacer(Modifier.padding(24.dp)) }
    }
    if (confirmAll) ConfirmDialog("Restore all ${snaps.size} packages?", "Every package changed by this app is returned to its recorded original state.",
        "Restore all", onConfirm = { vm.restoreMany(snaps.map { it.packageName }) }, onDismiss = { confirmAll = false })
}
