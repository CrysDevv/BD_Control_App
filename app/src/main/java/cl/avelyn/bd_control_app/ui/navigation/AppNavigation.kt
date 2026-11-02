package cl.avelyn.bd_control_app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cl.avelyn.bd_control_app.ui.screens.DashboardScreen
import cl.avelyn.bd_control_app.ui.screens.HistoryScreen
import cl.avelyn.bd_control_app.ui.screens.LoginScreen
import cl.avelyn.bd_control_app.ui.screens.SimulatorScreen

private object Routes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val HISTORY = "history"

    /** Pantalla de desarrollador, temporal mientras no hay ESP32: no requiere login. */
    const val SIMULATOR = "simulator"
}

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onOpenSimulator = { navController.navigate(Routes.SIMULATOR) }
            )
        }
        composable(Routes.SIMULATOR) {
            SimulatorScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) // limpia todo el back stack: no se puede volver con el botón atrás
                    }
                }
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
    }
}
