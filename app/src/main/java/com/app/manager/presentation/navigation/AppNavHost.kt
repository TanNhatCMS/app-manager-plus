package com.app.manager.presentation.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.app.manager.presentation.bloc.AppBloc
import com.app.manager.presentation.bloc.AppEvent
import com.app.manager.presentation.bloc.AppState
import com.app.manager.presentation.ui.screen.AppDetailScreen
import com.app.manager.presentation.ui.screen.MainScreen
import com.app.manager.presentation.ui.screen.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** The app list — the start destination. */
@Serializable
object AppListRoute

/** One app's detail page, addressed by package name (unique in this catalog). */
@Serializable
data class AppDetailRoute(val packageName: String)

@Serializable
object SettingsRoute

/**
 * The app's navigation graph.
 *
 * All three destinations share one [AppBloc], obtained here at the graph level. The detail screen
 * needs the live download progress and install status the bloc streams into the app list; a second
 * view model would have to duplicate the whole download and install pipeline to see them.
 */
@Composable
fun AppNavHost(
    bloc: AppBloc = hiltViewModel()
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Toasts are raised from downloads, installs and settings saves, all of which can now be
    // started from any screen — so they are shown here rather than on the list, which is not
    // composed while another destination is on top.
    val toastMessage by bloc.toastMessage.collectAsState()
    var currentToast by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(toastMessage) {
        toastMessage?.let { message ->
            currentToast?.cancel()
            val newToast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
            currentToast = newToast
            newToast.show()
            bloc.clearToast()
            delay(1000)
            newToast.cancel()
            currentToast = null
        }
    }

    NavHost(navController = navController, startDestination = AppListRoute) {
        composable<AppListRoute> {
            MainScreen(
                viewModel = bloc,
                onOpenDetail = { packageName -> navController.navigate(AppDetailRoute(packageName)) },
                onOpenSettings = { navController.navigate(SettingsRoute) }
            )
        }
        composable<AppDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<AppDetailRoute>()
            val state by bloc.state.collectAsState()
            when (val currentState = state) {
                is AppState.Success -> {
                    val app = currentState.apps.find { it.packageName == route.packageName }
                    if (app != null) {
                        AppDetailScreen(
                            app = app,
                            isFavorite = route.packageName in currentState.favorites,
                            bloc = bloc,
                            onBack = { navController.popBackStack() }
                        )
                    } else {
                        LaunchedEffect(route.packageName) {
                            navController.popBackStack()
                        }
                    }
                }
                else -> {
                    LaunchedEffect(route.packageName) {
                        navController.popBackStack()
                    }
                }
            }
        }
        composable<SettingsRoute> {
            val state by bloc.state.collectAsState()
            val config = when (val currentState = state) {
                is AppState.Success -> currentState.config
                is AppState.Error -> currentState.config
                else -> null
            }
            if (config != null) {
                SettingsScreen(
                    currentConfig = config,
                    onSave = { newConfig ->
                        bloc.handleEvent(AppEvent.SaveConfiguration(newConfig))
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() },
                    onClearCache = { bloc.handleEvent(AppEvent.ClearDownloadCache) }
                )
            }
        }
    }
}
