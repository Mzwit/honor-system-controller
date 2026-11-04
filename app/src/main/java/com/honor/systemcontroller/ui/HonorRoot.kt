package com.honor.systemcontroller.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.honor.systemcontroller.ui.screens.AppDetailScreen
import com.honor.systemcontroller.ui.screens.ApplicationsScreen
import com.honor.systemcontroller.ui.screens.AutomationScreen
import com.honor.systemcontroller.ui.screens.BackupScreen
import com.honor.systemcontroller.ui.screens.BlocklistScreen
import com.honor.systemcontroller.ui.screens.ConsoleScreen
import com.honor.systemcontroller.ui.screens.DashboardScreen
import com.honor.systemcontroller.ui.screens.ProcessesScreen
import com.honor.systemcontroller.ui.screens.SettingsScreen
import com.honor.systemcontroller.ui.screens.SystemPackagesScreen
import com.honor.systemcontroller.ui.theme.HonorTheme
import kotlinx.coroutines.launch

data class Dest(val route: String, val title: String, val icon: ImageVector)

val destinations = listOf(
    Dest("dashboard", "Dashboard", Icons.Filled.Dashboard),
    Dest("apps", "Applications", Icons.Filled.Apps),
    Dest("system", "System Packages", Icons.Filled.Shield),
    Dest("blocklist", "Blocklist", Icons.Filled.Block),
    Dest("processes", "Running Processes", Icons.Filled.Memory),
    Dest("automation", "Automation", Icons.Filled.Schedule),
    Dest("backup", "Backup & Recovery", Icons.Filled.Restore),
    Dest("settings", "Settings", Icons.Filled.Settings),
    Dest("console", "ADB / Shell Console", Icons.Filled.Terminal)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HonorRoot(vm: MainViewModel) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    HonorTheme(theme) {
        val nav = rememberNavController()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val busy by vm.busy.collectAsStateWithLifecycle()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: "dashboard"
        val isDetail = route.startsWith("app/")

        LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

        fun go(r: String) {
            nav.navigate(r) { popUpTo("dashboard") { saveState = true }; launchSingleTop = true; restoreState = true }
        }

        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = !isDetail,
            drawerContent = {
                ModalDrawerSheet {
                    Text("HONOR System Controller", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                    destinations.forEach { d ->
                        NavigationDrawerItem(
                            label = { Text(d.title) },
                            icon = { Icon(d.icon, null) },
                            selected = route == d.route,
                            onClick = { scope.launch { drawer.close() }; go(d.route) },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    Column {
                        TopAppBar(
                            title = { Text(if (isDetail) "App control" else destinations.firstOrNull { it.route == route }?.title ?: "") },
                            navigationIcon = {
                                if (isDetail) IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                                else IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menu") }
                            },
                            actions = {
                                if (route == "dashboard") IconButton({ go("console") }) { Icon(Icons.Filled.Terminal, "Console") }
                                if (route == "apps") IconButton({ go("system") }) { Icon(Icons.AutoMirrored.Filled.List, "System packages") }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                        )
                        if (busy != null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(busy ?: "", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    val open: (String) -> Unit = { pkg -> nav.navigate("app/$pkg") }
                    NavHost(nav, startDestination = "dashboard") {
                        composable("dashboard") { DashboardScreen(vm, ::go) }
                        composable("apps") { ApplicationsScreen(vm, open) }
                        composable("system") { SystemPackagesScreen(vm, open) }
                        composable("blocklist") { BlocklistScreen(vm, open) }
                        composable("processes") { ProcessesScreen(vm, open) }
                        composable("automation") { AutomationScreen(vm) }
                        composable("backup") { BackupScreen(vm) }
                        composable("settings") { SettingsScreen(vm) }
                        composable("console") { ConsoleScreen(vm) }
                        composable("app/{pkg}", arguments = listOf(navArgument("pkg") { type = NavType.StringType })) { be ->
                            AppDetailScreen(vm, be.arguments?.getString("pkg") ?: "")
                        }
                    }
                }
            }
        }
    }
}
