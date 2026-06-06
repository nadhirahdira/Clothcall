package com.clothcall

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.clothcall.ui.screens.VolumeCaptureBus
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.clothcall.telecom.TelecomHelper
import com.clothcall.ui.navigation.AppNavigation
import com.clothcall.ui.navigation.Route
import com.clothcall.ui.theme.ClothCallTheme
import com.clothcall.utils.PreferencesManager

class MainActivity : ComponentActivity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Intercept before any View (including PreviewView) can consume it.
        // Only on ACTION_DOWN so we don't fire twice (down + up).
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            && event.action == KeyEvent.ACTION_DOWN
        ) {
            val trigger = VolumeCaptureBus.trigger
            if (trigger != null) {
                trigger()
                return true  // consumed — volume bar never appears while camera is open
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        TelecomHelper.registerAccount(this)

        val prefs = PreferencesManager(this)
        val start = if (prefs.hasApiKey) Route.HOME else Route.API_KEY_SETUP

        setContent {
            ClothCallTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavigation(navController = navController, startDestination = start)
                }
            }
        }
    }
}
