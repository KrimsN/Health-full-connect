package dev.krimsn.healthconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.krimsn.healthconnect.ui.DashboardScreen
import dev.krimsn.healthconnect.ui.HealthSyncViewModel
import dev.krimsn.healthconnect.ui.HistoryScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    HealthSyncApp()
                }
            }
        }
    }
}

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_HISTORY = "history"

@Composable
private fun HealthSyncApp() {
    val navController = rememberNavController()
    val viewModel: HealthSyncViewModel = viewModel()

    NavHost(navController = navController, startDestination = ROUTE_DASHBOARD) {
        composable(ROUTE_DASHBOARD) {
            DashboardScreen(
                viewModel = viewModel,
                onOpenHistory = { navController.navigate(ROUTE_HISTORY) },
            )
        }
        composable(ROUTE_HISTORY) {
            HistoryScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
