package com.honor.systemcontroller.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honor.systemcontroller.core.classify.PackageCategory
import com.honor.systemcontroller.core.classify.Risk
import com.honor.systemcontroller.core.packages.AppAction
import com.honor.systemcontroller.core.packages.AppEntry
import com.honor.systemcontroller.core.packages.PackageController
import com.honor.systemcontroller.core.privilege.PrivilegeMode
import com.honor.systemcontroller.ui.AppFilter
import com.honor.systemcontroller.ui.MainViewModel
import com.honor.systemcontroller.ui.components.AppIcon
import com.honor.systemcontroller.ui.components.ConfirmDialog
import com.honor.systemcontroller.ui.components.KeyValue
import com.honor.systemcontroller.ui.components.Mono
import com.honor.systemcontroller.ui.components.SectionCard
import com.honor.systemcontroller.ui.components.StatusChip
import com.honor.systemcontroller.ui.components.formatBytes
import com.honor.systemcontroller.ui.components.formatDuration
import com.honor.systemcontroller.ui.components.riskColor
import com.honor.systemcontroller.ui.theme.Danger
import com.honor.systemcontroller.ui.theme.Info
import com.honor.systemcontroller.ui.theme.Ok
import com.honor.systemcontroller.ui.theme.Warn
import java.text.DateFormat

@Composable
fun AppRow(a: AppEntry, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(a.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                StatusChip(a.stateLabel, when { !a.installedForUser || a.isDisabled -> Danger; a.suspended -> Warn; a.running == true -> Ok; else -> Info })
                StatusChip(if (a.isSystem) "System" else "User", if (a.isSystem) Warn else Info)
                if (a.rssKb > 0) StatusChip("${a.rssKb / 1024} MB", Info)
            }
        }
        trailing()
    }
}

@Composable
fun SearchField(q: String, onChange: (String) -> Unit) {
    OutlinedTextField(q, onChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search name or package") })
}

@Composable
fun ApplicationsScreen(vm: MainViewModel, open: (String) -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    val list = remember(apps, q, filter) { vm.filtered(apps, filter, q) }
    Column(Modifier.fillMaxSize()) {
        SearchField(q) { q = it }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppFilter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f.title) }) }
        }
        Text(if (loading) "Loading…" else "${list.size} applications", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
        if ((filter == AppFilter.RUNNING || filter == AppFilter.BACKGROUND) && apps.none { it.running != null })
            Text("Running state needs Shizuku or root (Android hides other apps' processes).", Modifier.padding(16.dp), color = Warn)
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.packageName }) { a -> AppRow(a, { open(a.packageName) }) }
        }
    }
}

@Composable
fun SystemPackagesScreen(vm: MainViewModel, open: (String) -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<PackageCategory?>(null) }
    val sys = remember(apps, q, cat) {
        apps.filter { it.isSystem && (cat == null || it.classification.category == cat) &&
            (q.isBlank() || it.label.contains(q, true) || it.packageName.contains(q, true)) }
            .sortedWith(compareBy({ it.classification.category.ordinal }, { it.classification.risk.ordinal }, { it.label.lowercase() }))
    }
    Column(Modifier.fillMaxSize()) {
        SearchField(q) { q = it }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(cat == null, { cat = null }, { Text("All") })
            PackageCategory.entries.filter { it != PackageCategory.USER }.forEach { c ->
                FilterChip(cat == c, { cat = c }, { Text("${c.title} (${apps.count { a -> a.isSystem && a.classification.category == c }})") })
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            var last: PackageCategory? = null
            sys.forEach { a ->
                if (a.classification.category != last) {
                    last = a.classification.category
                    item(key = "h_" + a.classification.category.name) {
                        Text(a.classification.category.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
                    }
                }
                item(key = a.packageName) {
                    AppRow(a, { open(a.packageName) }) { StatusChip(a.classification.risk.title, riskColor(a.classification.risk)) }
                }
            }
        }
    }
}

private data class Pending(val action: AppAction, val typed: String?)

@Composable
fun AppDetailScreen(vm: MainViewModel, pkg: String) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val rootOk by vm.rootVerified.collectAsStateWithLifecycle()
    val a = apps.firstOrNull { it.packageName == pkg }
    if (a == null) { Text("Package not found: $pkg", Modifier.padding(16.dp)); return }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var lastResult by remember { mutableStateOf<String?>(null) }
    var showBlock by remember { mutableStateOf(false) }
    var confirmRootRemove by remember { mutableStateOf(false) }
    val components = remember { mutableStateListOf<PackageController.ComponentItem>() }
    var showComponents by remember { mutableStateOf(false) }
    LaunchedEffect(showComponents) { if (showComponents) { components.clear(); components.addAll(vm.c.controller.components(pkg)) } }
    val cls = a.classification
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                AppIcon(a.packageName, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(a.label, style = MaterialTheme.typography.headlineSmall)
                    Text(a.packageName, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        StatusChip(a.stateLabel, if (a.isDisabled || !a.installedForUser) Danger else Ok)
                        StatusChip(cls.risk.title, riskColor(cls.risk))
                    }
                }
            }
        }
        item {
            SectionCard("Information") {
                KeyValue("Version", "${a.versionName} (${a.versionCode})")
                KeyValue("Install type", a.installType)
                KeyValue("Category", cls.category.title)
                KeyValue("Known purpose", cls.purpose)
                KeyValue("Subsystems", cls.subsystems.joinToString().ifBlank { "none detected" })
                KeyValue("Enabled setting", PackageController.settingName(a.enabledSetting))
                KeyValue("Storage", formatBytes(a.storageBytes))
                KeyValue("Foreground 24 h", formatDuration(a.foregroundMs24h))
                KeyValue("Last used", if (a.lastTimeUsed > 0) df.format(a.lastTimeUsed) else "—")
                KeyValue("Memory (RSS)", if (a.running == null) "needs Shizuku/root" else if (a.rssKb > 0) "${a.rssKb / 1024} MB" else "not running")
                KeyValue("Installed", df.format(a.firstInstall))
                KeyValue("APK", a.sourceDir)
            }
        }
        item {
            SectionCard("Impact analysis") {
                Text(cls.consequences, style = MaterialTheme.typography.bodyMedium, color = riskColor(cls.risk))
                Text("Mode: ${mode.title} — ${vm.c.privilege.capabilityLine()}", style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            SectionCard("Actions") {
                val actions = buildList {
                    add(AppAction.FORCE_STOP)
                    if (a.installedForUser) { if (a.isDisabled) add(AppAction.ENABLE) else add(AppAction.DISABLE) }
                    if (a.suspended) add(AppAction.UNBLOCK_LAUNCH) else add(AppAction.BLOCK_LAUNCH)
                    add(AppAction.RESTRICT_BACKGROUND); add(AppAction.ALLOW_BACKGROUND)
                    if (a.installedForUser) add(AppAction.UNINSTALL) else add(AppAction.REINSTALL_SYSTEM)
                    add(AppAction.RESTORE)
                }
                actions.forEach { act ->
                    val blocked = vm.c.controller.guard(a, act)
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        val destructive = act == AppAction.DISABLE || act == AppAction.UNINSTALL || act == AppAction.BLOCK_LAUNCH
                        Button(
                            onClick = {
                                val typed = if ((a.isSystem && destructive && cls.risk != Risk.SAFE) || (act == AppAction.UNINSTALL)) a.packageName else null
                                pending = Pending(act, typed)
                            },
                            enabled = blocked == null,
                            colors = if (destructive) ButtonDefaults.buttonColors(containerColor = Danger) else ButtonDefaults.buttonColors(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(act.title) }
                        Text(blocked ?: act.explanation, style = MaterialTheme.typography.labelSmall,
                            color = if (blocked != null) Danger else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (blocked == null) Text(vm.c.controller.availability(act), style = MaterialTheme.typography.labelSmall, color = Info)
                    }
                }
                OutlinedButton({ vm.c.controller.openAppInfo(pkg) }, Modifier.fillMaxWidth()) { Text("Open system App info") }
                OutlinedButton({ showBlock = true }, Modifier.fillMaxWidth()) { Text("Add to blocklist…") }
            }
        }
        if (a.isSystem) item {
            SectionCard("Remove from system partition (root)") {
                val pre = vm.c.rootDebloat.precheck(a)
                Text("Hides this app's APK folder from /system, /product or /system_ext at every boot using a systemless Magisk/KernelSU module, after backing the APK up to /sdcard/HonorSystemController/apk-backup. Needs a reboot. Undo from Backup & Recovery.",
                    style = MaterialTheme.typography.bodyMedium)
                Button({ confirmRootRemove = true }, Modifier.fillMaxWidth(), enabled = pre == null,
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Delete system APK (root)") }
                Text(pre ?: "Available — root verified (${if (rootOk) "yes" else "no"})", style = MaterialTheme.typography.labelSmall,
                    color = if (pre != null) Danger else Ok)
            }
        }
        item {
            SectionCard("Components") {
                TextButton({ showComponents = !showComponents }) { Text(if (showComponents) "Hide components" else "Show services / receivers / activities") }
                if (showComponents) {
                    if (components.isEmpty()) Text("No components visible.")
                    components.forEach { comp ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(comp.className.removePrefix(pkg), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(comp.type, style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(comp.enabled, { en ->
                                vm.setComponent(a, comp.className, en) { r ->
                                    if (r.success) { components.clear(); components.addAll(vm.c.controller.components(pkg)) }
                                }
                            }, enabled = mode != PrivilegeMode.STANDARD && cls.risk != Risk.CRITICAL)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        lastResult?.let { item { SectionCard("Last result") { Mono(it) } } }
        item { Spacer(Modifier.padding(24.dp)) }
    }

    pending?.let { p ->
        val body = buildString {
            append(p.action.explanation).append("\n\n")
            if (p.action == AppAction.UNINSTALL) append(if (a.isSystem) "System app: removed for your user; APK kept so it can be reinstalled. App data is deleted.\n\n"
                else "User app: the APK and ALL its data are deleted permanently. Restore cannot bring it back.\n\n")
            if (a.isSystem) append(cls.consequences).append("\n\n")
            append("Commands:\n").append(vm.c.controller.commandsFor(a.packageName, p.action).joinToString("\n"))
        }
        ConfirmDialog(
            title = "${p.action.title} – ${a.label}?", body = body, confirmLabel = p.action.title,
            destructive = p.action == AppAction.DISABLE || p.action == AppAction.UNINSTALL || p.action == AppAction.BLOCK_LAUNCH,
            typeToConfirm = p.typed,
            onConfirm = { vm.perform(a, p.action) { r -> lastResult = (if (r.success) "✔ " else "✖ ") + r.message + "\n\n" + r.command } },
            onDismiss = { pending = null }
        )
    }
    if (confirmRootRemove) ConfirmDialog(
        title = "Delete ${a.label} from the system partition?",
        body = "Package: ${a.packageName}\nAPK: ${a.sourceDir}\n\n${cls.consequences}\n\nThe APK folder is backed up first and the removal is applied by a Magisk/KernelSU module at the next boot. If the phone fails to boot, start Magisk safe mode (hold Volume Down while booting) – this disables the module.",
        confirmLabel = "Delete with root", destructive = true, typeToConfirm = a.packageName,
        onConfirm = { vm.rootRemove(a) },
        onDismiss = { confirmRootRemove = false }
    )
    if (showBlock) BlockDialog(a, onDismiss = { showBlock = false }) { d, s, r, f -> vm.addToBlocklist(a, d, s, r, f) }
}

@Composable
fun BlockDialog(a: AppEntry, onDismiss: () -> Unit, onConfirm: (Boolean, Boolean, Boolean, Boolean) -> Unit) {
    var disable by remember { mutableStateOf(a.classification.risk == Risk.SAFE) }
    var suspend by remember { mutableStateOf(true) }
    var bg by remember { mutableStateOf(true) }
    var stop by remember { mutableStateOf(true) }
    val critical = a.classification.risk == Risk.CRITICAL
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block ${a.label}") },
        text = {
            Column {
                if (critical) Text("This is a critical package and cannot be blocked.", color = Danger)
                listOf(
                    Triple("Restrict background execution (appops + standby bucket)", bg) { v: Boolean -> bg = v },
                    Triple("Block launching (pm suspend)", suspend) { v: Boolean -> suspend = v },
                    Triple("Disable package (pm disable-user)", disable) { v: Boolean -> disable = v },
                    Triple("Force-stop now", stop) { v: Boolean -> stop = v }
                ).forEach { (label, v, set) ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(v, set, enabled = !critical); Text(label) }
                }
                Text("Previous state is recorded and restrictions are re-checked by the automation engine.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton({ onConfirm(disable, suspend, bg, stop); onDismiss() }, enabled = !critical) { Text("Add & apply") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
