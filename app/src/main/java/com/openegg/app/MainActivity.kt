package com.openegg.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openegg.app.map.MapInit
import com.openegg.app.ui.LinkTestScreen
import com.openegg.app.ui.map.OfflineMapsScreen
import com.openegg.app.ui.StartScreen
import com.openegg.app.ui.flight.FlightScreen
import com.openegg.app.ui.flight.FlightViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapInit.ensure(applicationContext)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { App() }
            }
        }
    }

    @Composable
    private fun App() {
        var screen by rememberSaveable { mutableStateOf("start") }
        val flight: FlightViewModel = viewModel()

        when (screen) {
            "flight" -> {
                FlightWindow()
                val exit = { flight.disconnect(); screen = "start" }
                BackHandler(onBack = exit)
                FlightScreen(flight, onExit = exit)
            }
            "linktest" -> {
                BackHandler { screen = "start" }
                LinkTestScreen()
            }
            "maps" -> {
                BackHandler { screen = "start" }
                OfflineMapsScreen(onBack = { screen = "start" })
            }
            else -> StartScreen(
                onFly = { source -> flight.connect(source); screen = "flight" },
                onLinkTest = { screen = "linktest" },
                onOfflineMaps = { screen = "maps" },
            )
        }
    }

    /** Immersive full screen and screen-always-on while the flight view is up. */
    @Composable
    private fun FlightWindow() {
        DisposableEffect(Unit) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}
