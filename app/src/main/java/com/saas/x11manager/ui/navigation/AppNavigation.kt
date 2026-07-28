package com.saas.x11manager.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.saas.x11manager.ui.screen.HomeScreen
import com.saas.x11manager.ui.screen.HomeViewModel
import com.saas.x11manager.ui.screen.SystemInfoScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object SystemInfo : Screen("system_info")
}

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Home, "Home", Icons.Filled.Computer, Icons.Outlined.Computer),
    BottomNavItem(Screen.SystemInfo, "System Info", Icons.Filled.Info, Icons.Outlined.Info)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(viewModel: HomeViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("X11 Manager", fontWeight = FontWeight.Bold)
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp
            ) {
                bottomNavItems.forEach { item ->
                    val selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.label
                            )
                        },
                        label = { Text(item.label) },
                        selected = selected,
                        onClick = {
                            navController.navigate(item.screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(viewModel = viewModel)
            }
            composable(Screen.SystemInfo.route) {
                SystemInfoScreen(viewModel = viewModel)
            }
        }
    }
}
