package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.ServersScreen
import com.example.ui.screens.SplitTunnelingScreen
import com.example.ui.screens.SubscriptionsScreen
import com.example.ui.theme.*
import com.example.ui.viewmodel.VpnViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            XrayVpnTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }
}

sealed class NavItem(val route: String, val title: String, val icon: ImageVector) {
    object Home : NavItem("home", "Connect", Icons.Default.Shield)
    object Servers : NavItem("servers", "Servers", Icons.Default.Dns)
    object Split : NavItem("split", "Split Tunnel", Icons.Default.AltRoute)
    object Subscriptions : NavItem("subscriptions", "Subscriptions", Icons.Default.RssFeed)
    object Logs : NavItem("logs", "Logs", Icons.Default.Terminal)
}

@Composable
fun MainAppScreen(viewModel: VpnViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: NavItem.Home.route

    val items = listOf(
        NavItem.Home,
        NavItem.Servers,
        NavItem.Split,
        NavItem.Subscriptions,
        NavItem.Logs
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = CyberBackground,
        bottomBar = {
            NavigationBar(
                containerColor = CyberSurface,
                contentColor = CyberCyan,
                tonalElevation = 8.dp
            ) {
                items.forEach { item ->
                    val isSelected = currentRoute == item.route
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            if (currentRoute != item.route) {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title
                            )
                        },
                        label = {
                            Text(
                                text = item.title,
                                color = if (isSelected) CyberCyan else TextSecondary
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyberCyan,
                            selectedTextColor = CyberCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = CyberSurfaceVariant
                        ),
                        modifier = Modifier.testTag("nav_tab_${item.route}")
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavItem.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(NavItem.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToServers = {
                        navController.navigate(NavItem.Servers.route)
                    },
                    onNavigateToSplitTunnel = {
                        navController.navigate(NavItem.Split.route)
                    }
                )
            }

            composable(NavItem.Servers.route) {
                ServersScreen(
                    viewModel = viewModel,
                    onServerSelected = {
                        navController.navigate(NavItem.Home.route)
                    }
                )
            }

            composable(NavItem.Split.route) {
                SplitTunnelingScreen(viewModel = viewModel)
            }

            composable(NavItem.Subscriptions.route) {
                SubscriptionsScreen(viewModel = viewModel)
            }

            composable(NavItem.Logs.route) {
                LogsScreen()
            }
        }
    }
}
