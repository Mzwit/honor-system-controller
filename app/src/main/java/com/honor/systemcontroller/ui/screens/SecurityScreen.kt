package com.honor.systemcontroller.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.honor.systemcontroller.core.security.SecurityInspector
import com.honor.systemcontroller.ui.MainViewModel
import com.honor.systemcontroller.ui.components.AppIcon
import com.honor.systemcontroller.ui.components.ConfirmDialog
import com.honor.systemcontroller.ui.components.SectionCard
import com.honor.systemcontroller.ui.components.StatusChip
import com.honor.systemcontroller.ui.theme.Danger
import com.honor.systemcontroller.ui.theme.Info
import com.honor.systemcontroller.ui.theme.Ok
import com.honor.systemcontroller.ui.theme.Warn

@Composable
fun SecurityScreen(vm: MainViewModel) {
    val admins by vm.admins.collectAsStateWithLifecycle()
    val access by vm.accessibility.collectAsStateWithLifecycle()
    val restricted by vm.debugRestricted.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<SecurityInspector.AdminApp?>(null) }
    LaunchedEffect(Unit) { vm.refreshSecurity() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        SectionCard("Why Developer options won't open") {
            if (restricted) {
                Text("A device administrator on this phone has switched on the \"block debugging features\" policy. That is why tapping Build number does nothing. Remove the admin below and the block lifts.",
                    color = Warn, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("No debugging-restriction policy is currently active. If Build number still does nothing, check the accessibility services below — malware can tap \"cancel\" faster than you can.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            TextButton({ vm.refreshSecurity() }) { Text("Re-scan") }
        }

        SectionCard("Device administrators (${admins.size})") {
            Text("Apps here can lock, wipe or restrict the phone. Malware hides here to stop you removing it. Anything not recognised should be removed.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (admins.isEmpty()) Text("None active.")
            admins.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(a.packageName, 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = MaterialTheme.typography.titleMedium)
                        Text(a.packageName, style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                            when {
                                a.isDeviceOwner -> StatusChip("Device Owner", Danger)
                                a.isProfileOwner -> StatusChip("Profile Owner", Warn)
                                a.wellKnown -> StatusChip("Recognised", Ok)
                                else -> StatusChip("Unrecognised", Danger)
                            }
                            if (a.blocksDebugging) StatusChip("Blocks debugging", Warn)
                        }
                    }
                }
                if (!a.wellKnown) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ confirmRemove = a }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Remove admin") }
                    OutlinedButton({ vm.openSecurityAppInfo(a.packageName) }) { Text("App info") }
                }
            }
            OutlinedButton({ vm.openDeviceAdminSettings() }, Modifier.fillMaxWidth()) { Text("Open system Device admin screen") }
        }

        SectionCard("Accessibility services (${access.size})") {
            Text("A service here can read your screen and tap buttons for you. Legitimate for screen readers — abused by malware to auto-dismiss removal and intercept taps.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (access.isEmpty()) Text("None enabled.")
            access.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(s.packageName, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = MaterialTheme.typography.bodyMedium)
                        Text(s.packageName, style = MaterialTheme.typography.labelSmall)
                    }
                    StatusChip(if (s.wellKnown) "System" else "Third-party", if (s.wellKnown) Ok else Danger)
                }
            }
            OutlinedButton({ vm.openAccessibilitySettings() }, Modifier.fillMaxWidth()) { Text("Open Accessibility settings") }
        }

        SectionCard("If it keeps coming back") {
            Text("1. Boot into Safe Mode (hold Power, then long-press \"Power off\" → Safe mode). Third-party apps don't run there.\n" +
                 "2. Come back here, Remove admin, then uninstall the app.\n" +
                 "3. Reboot normally and run Play Protect.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }

    confirmRemove?.let { a ->
        ConfirmDialog(
            title = "Remove ${a.label} as administrator?",
            body = "Package: ${a.packageName}\n\nThis strips its administrator powers so you can uninstall it. " +
                (if (vm.c.privilege.executor() != null) "Shizuku/root will run: dpm remove-active-admin ${a.component}" else "You'll be taken to the system screen to deactivate it.") +
                "\n\nAfter it's removed, uninstall the app from App info.",
            confirmLabel = "Remove admin", destructive = true, typeToConfirm = a.packageName,
            onConfirm = { vm.forceRemoveAdmin(a) },
            onDismiss = { confirmRemove = null }
        )
    }
}
