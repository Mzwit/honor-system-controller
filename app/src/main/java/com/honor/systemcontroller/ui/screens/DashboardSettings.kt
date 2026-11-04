package com.honor.systemcontroller.ui.screens

import android.Manifest
import android.os.Build
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honor.systemcontroller.core.magicos.MagicOsCompat
import com.honor.systemcontroller.core.privilege.PrivilegeMode
import com.honor.systemcontroller.core.privilege.RootExecutor
import com.honor.systemcontroller.ui.MainViewModel
import com.honor.systemcontroller.ui.components.ConfirmDialog
import com.honor.systemcontroller.ui.components.KeyValue
import com.honor.systemcontroller.ui.components.LineGraph
import com.honor.systemcontroller.ui.components.Mono
import com.honor.systemcontroller.ui.components.SectionCard
import com.honor.systemcontroller.ui.components.StatTile
import com.honor.systemcontroller.ui.components.StatusChip
import com.honor.systemcontroller.ui.theme.Danger
import com.honor.systemcontroller.ui.theme.Info
import com.honor.systemcontroller.ui.theme.Ok
import com.honor.systemcontroller.ui.theme.Warn

@Composable
fun DashboardScreen(vm: MainViewModel, go: (String) -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val shStatus by vm.shizukuStatus.collectAsStateWithLifecycle()
    val shReady by vm.shizukuReady.collectAsStateWithLifecycle()
    val mem by vm.memory.collectAsStateWithLifecycle()
    val hist by vm.memHistory.collectAsStateWithLifecycle()
    val usage by vm.usageAccess.collectAsStateWithLifecycle()
    val block by vm.blocklist.collectAsStateWithLifecycle()
    val snaps by vm.snapshots.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(MagicOsCompat.deviceSummary(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Installed", apps.count { it.installedForUser }.toString(), Info, Modifier.weight(1f))
            StatTile("System", apps.count { it.isSystem }.toString(), Warn, Modifier.weight(1f))
            StatTile("Disabled", apps.count { it.isDisabled || !it.installedForUser }.toString(), Danger, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Running", apps.count { it.running == true }.let { if (apps.any { a -> a.running != null }) it.toString() else "—" }, Ok, Modifier.weight(1f))
            StatTile("Blocklist", block.size.toString(), Danger, Modifier.weight(1f))
            StatTile("Modified", snaps.size.toString(), Info, Modifier.weight(1f))
        }

        SectionCard("Privilege mode") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(mode.title, if (vm.c.privilege.executor() != null) Ok else Warn)
                Spacer(Modifier.width(8.dp))
                Text(vm.c.privilege.capabilityLine(), style = MaterialTheme.typography.bodyMedium)
            }
            Text("Shizuku: $shStatus", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!shReady) OutlinedButton({ MagicOsCompat.openShizuku(ctx); vm.requestShizuku() }) { Text("Connect Shizuku") }
                OutlinedButton({ go("settings") }) { Text("Change mode") }
            }
        }

        SectionCard("Memory") {
            Text("${mem.totalMb - mem.availMb} MB used of ${mem.totalMb} MB" + if (mem.lowMemory) " · LOW MEMORY" else "", style = MaterialTheme.typography.bodyMedium)
            LineGraph(hist, MaterialTheme.colorScheme.primary)
            TextButton({ vm.refreshProcesses(); go("processes") }) { Text("Open running processes") }
        }

        if (!usage) SectionCard("Usage access needed") {
            Text("Grant usage access to see foreground time, storage per app and blocklist activity alerts. No elevated privilege required.")
            Button({ MagicOsCompat.openUsageAccess(ctx) }) { Text("Grant usage access") }
        }

        SectionCard("Quick actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ go("system") }) { Text("Debloat") }
                OutlinedButton({ vm.checkBlocklist() }) { Text("Verify blocklist") }
                OutlinedButton({ go("backup") }) { Text("Restore") }
            }
            TextButton({ MagicOsCompat.openAppLaunchManager(ctx) }) { Text("Open MagicOS App launch manager") }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val rootOk by vm.rootVerified.collectAsStateWithLifecycle()
    val shStatus by vm.shizukuStatus.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirmRoot by remember { mutableStateOf(false) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Operating mode") {
            PrivilegeMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().clickable {
                    if (m == PrivilegeMode.ROOT && !rootOk) confirmRoot = true else vm.setMode(m)
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(selected = mode == m, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(m.title, style = MaterialTheme.typography.titleMedium)
                        Text(m.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            KeyValue("Shizuku", shStatus)
            KeyValue("su binary", if (RootExecutor.suBinaryPresent()) "present" else "not found")
            KeyValue("Root", if (rootOk) "verified" else "not verified")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ MagicOsCompat.openShizuku(ctx); vm.requestShizuku() }) { Text("Shizuku") }
                OutlinedButton({ MagicOsCompat.openDeveloperOptions(ctx) }) { Text("Developer options") }
            }
        }
        SectionCard("Theme") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (k, l) ->
                    FilterChip(selected = theme == k, onClick = { vm.setTheme(k) }, label = { Text(l) })
                }
            }
        }
        SectionCard("Permissions") {
            OutlinedButton({ MagicOsCompat.openUsageAccess(ctx) }) { Text("Usage access") }
            if (Build.VERSION.SDK_INT >= 33) OutlinedButton({ notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
            OutlinedButton({ MagicOsCompat.openAppLaunchManager(ctx) }) { Text("MagicOS App launch (keep this app alive)") }
            KeyValue("Battery unrestricted", if (MagicOsCompat.isSelfBatteryUnrestricted(ctx)) "yes" else "no")
        }
        SectionCard("HONOR 400 / MagicOS 9 notes") {
            MagicOsCompat.notes.forEach { (t, d) ->
                Text(t, style = MaterialTheme.typography.titleMedium)
                Text(d, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmRoot) ConfirmDialog(
        title = "Use root?",
        body = "This app never roots your phone. If it is ALREADY rooted (Magisk/KernelSU), your root manager will ask you to grant access.\n\nRoot can modify anything on the device. A wrong change to a system package can stop the phone from booting. Every action still goes through the same protections and backups.",
        confirmLabel = "Verify root",
        destructive = true,
        onConfirm = { vm.setMode(PrivilegeMode.ROOT); vm.verifyRoot() },
        onDismiss = { confirmRoot = false }
    )
}

@Composable
fun ConsoleScreen(vm: MainViewModel) {
    val lines by vm.console.collectAsStateWithLifecycle()
    var cmd by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Runs allowlisted package-management commands through Shizuku/root. Without a shell, the command is copied as `adb shell …` for your computer.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("pm list packages -s", "pm list packages -3", "pm list packages -d", "id").forEach { s ->
                FilterChip(false, { cmd = s }, { Text(s, style = MaterialTheme.typography.labelSmall) })
            }
        }
        OutlinedTextField(cmd, { cmd = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Command") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { vm.runConsole(cmd) }))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.runConsole(cmd) }) { Text("Run") }
            OutlinedButton({ vm.clearConsole() }) { Text("Clear") }
        }
        SectionCard(Modifier.weight(1f)) {
            LazyColumn(state = list) { items(lines) { Mono(it) } }
        }
    }
}

@Composable
private fun SectionCard(modifier: Modifier, content: @Composable () -> Unit) = SectionCard(null, modifier, content)
