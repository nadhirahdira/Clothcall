package com.clothcall.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.clothcall.ClothCallApplication
import com.clothcall.api.GeminiApiService
import com.clothcall.ui.screens.*
import com.clothcall.ui.viewmodels.*
import com.clothcall.utils.PreferencesManager

object Route {
    const val API_KEY_SETUP = "api_key_setup"
    const val HOME = "home"
    const val QUICK_SCAN = "quick_scan"
    const val CALL_UI = "call_ui"
    const val WARDROBE = "wardrobe"
    const val CAREGIVER_SETUP = "caregiver_setup"
}

@Composable
fun AppNavigation(navController: NavHostController, startDestination: String) {
    val context = LocalContext.current
    val app = context.applicationContext as ClothCallApplication
    val prefs = remember { PreferencesManager(context) }
    val apiService = remember { GeminiApiService() }

    val caregiverDao = app.database.caregiverProfileDao()
    val garmentDao = app.database.garmentDao()

    // Shared ScanViewModel so QuickScan and CallUI see the same result
    val scanViewModel: ScanViewModel = viewModel(
        factory = ScanViewModel.factory(apiService, caregiverDao, garmentDao, prefs)
    )
    val callViewModel: CallViewModel = viewModel(
        factory = CallViewModel.factory(apiService, prefs)
    )
    val wardrobeViewModel: WardrobeViewModel = viewModel(
        factory = WardrobeViewModel.factory(garmentDao)
    )

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Route.API_KEY_SETUP) {
            ApiKeySetupScreen(
                navController = navController,
                onSave = { key -> prefs.apiKey = key }
            )
        }

        composable(Route.HOME) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = HomeViewModel.factory(caregiverDao, garmentDao, prefs)
            )
            HomeScreen(navController = navController, viewModel = homeViewModel)
        }

        composable(Route.QUICK_SCAN) {
            QuickScanScreen(
                navController = navController,
                viewModel = scanViewModel,
                isOutMode = prefs.isOutMode
            )
        }

        composable(Route.CALL_UI) {
            CallUIScreen(
                navController = navController,
                isOutMode = prefs.isOutMode,
                viewModel = callViewModel
            )
        }

        composable(Route.WARDROBE) {
            WardrobeScreen(navController = navController, viewModel = wardrobeViewModel)
        }

        composable(Route.CAREGIVER_SETUP) {
            val caregiverViewModel: CaregiverViewModel = viewModel(
                factory = CaregiverViewModel.factory(caregiverDao)
            )
            CaregiverSetupScreen(navController = navController, viewModel = caregiverViewModel)
        }
    }
}
