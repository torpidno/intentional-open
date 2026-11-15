package com.pause.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pause.app.ui.apps.AppPickerScreen
import com.pause.app.ui.history.HistoryScreen
import com.pause.app.ui.home.HomeScreen
import com.pause.app.ui.settings.SettingsScreen

object PauseDestinations {
    const val HOME = "home"
    const val APP_PICKER = "app_picker"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
}

@Composable
fun PauseNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = PauseDestinations.HOME,
    ) {
        composable(PauseDestinations.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(PauseDestinations.SETTINGS) },
                onAddApps = { navController.navigate(PauseDestinations.APP_PICKER) },
                onOpenHistory = { navController.navigate(PauseDestinations.HISTORY) },
            )
        }
        composable(PauseDestinations.APP_PICKER) {
            AppPickerScreen(onBack = { navController.popBackStack() })
        }
        composable(PauseDestinations.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
        composable(PauseDestinations.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
