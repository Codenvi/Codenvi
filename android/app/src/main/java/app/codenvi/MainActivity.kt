package app.codenvi

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.codenvi.ui.AppViewModel
import app.codenvi.ui.components.Toast
import app.codenvi.ui.components.ToastHost
import app.codenvi.ui.components.ToastKind
import app.codenvi.ui.screens.ChatScreen
import app.codenvi.ui.screens.ConnectScreen
import app.codenvi.ui.screens.DevicesScreen
import app.codenvi.ui.screens.MultimodeScreen
import app.codenvi.ui.screens.ProjectScreen
import app.codenvi.ui.screens.ProjectsScreen
import app.codenvi.ui.screens.SettingsScreen
import app.codenvi.ui.theme.CodenviTheme
import app.codenvi.ui.theme.Tokens

class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark status and navigation bars (docs/design.md §0.7).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#121212")),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#121212")),
        )
        super.onCreate(savedInstanceState)
        pendingLink = intent?.dataString
        setContent { CodenviTheme { Root(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLink = intent.dataString
    }

    /** FLAG_SECURE on screens that show or accept keys (docs/design.md §10.6). */
    fun setSecure(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun consumeLink(): String? = pendingLink.also { pendingLink = null }
    val link: String? get() = pendingLink
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("devices", "Devices", Icons.Filled.Home),
    Tab("multimode", "Multimode", Icons.Filled.List),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
private fun Root(activity: MainActivity) {
    val vm: AppViewModel = viewModel(factory = AppViewModel.Factory)
    val nav = rememberNavController()
    var toast by remember { mutableStateOf<Toast?>(null) }
    val secure by vm.repo.secure.collectAsStateWithLifecycle()
    val revoked by vm.repo.revoked.collectAsStateWithLifecycle()
    val upgrade by vm.repo.upgradeRequired.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    LaunchedEffect(Unit) { vm.messages.collect { toast = it } }
    LaunchedEffect(revoked) { if (revoked) toast = Toast("This app was disconnected. Pair it again from a PC (Settings → Disconnect this phone).", ToastKind.Error) }
    LaunchedEffect(upgrade) { if (upgrade) toast = Toast("Update Codenvi - the server no longer accepts this version.", ToastKind.Warning) }

    // codenvi:// links: a key-only string goes to Devices (add key), a pairing string to Connect.
    val link = activity.link
    LaunchedEffect(link) {
        if (link != null && secure.refreshToken != null && link.startsWith("codenvi://key", ignoreCase = true)) {
            vm.run { vm.repo.addKeyString(link) }
            activity.consumeLink()
        }
    }

    val start = if (!secure.disclaimerAccepted || secure.refreshToken == null) "connect" else "devices"
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Tokens.Background,
        bottomBar = {
            if (route in tabs.map { it.route }) {
                NavigationBar(containerColor = Tokens.Surface) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.navigate(t.route) { popUpTo("devices"); launchSingleTop = true } },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Tokens.OnPrimary, indicatorColor = Tokens.Primary,
                                selectedTextColor = Tokens.Primary, unselectedIconColor = Tokens.OnSurfaceMuted,
                                unselectedTextColor = Tokens.OnSurfaceMuted,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = start, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            composable("connect") {
                ConnectScreen(vm, activity, initialLink = activity.consumeLink()?.takeIf { !it.startsWith("codenvi://key", true) }) {
                    nav.navigate("devices") { popUpTo("connect") { inclusive = true } }
                }
            }
            composable("devices") { DevicesScreen(vm, activity, onOpen = { nav.navigate("projects/$it") }) }
            composable("projects/{deviceId}", listOf(navArgument("deviceId") { type = NavType.StringType })) { e ->
                ProjectsScreen(vm, e.arguments!!.getString("deviceId")!!, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("project/$it") })
            }
            composable("project/{projectId}", listOf(navArgument("projectId") { type = NavType.StringType })) { e ->
                ProjectScreen(vm, e.arguments!!.getString("projectId")!!, onBack = { nav.popBackStack() }, onChat = { nav.navigate("chat/$it") })
            }
            composable("chat/{sessionId}", listOf(navArgument("sessionId") { type = NavType.StringType })) { e ->
                ChatScreen(vm, e.arguments!!.getString("sessionId")!!, onBack = { nav.popBackStack() })
            }
            composable("multimode") { MultimodeScreen(vm, onOpenChat = { nav.navigate("chat/$it") }) }
            composable("settings") {
                SettingsScreen(vm, onDisconnected = { nav.navigate("connect") { popUpTo(0) { inclusive = true } } })
            }
        }
    }
    ToastHost(toast, onDismiss = { toast = null }, modifier = Modifier.align(Alignment.TopCenter))
    }
}
