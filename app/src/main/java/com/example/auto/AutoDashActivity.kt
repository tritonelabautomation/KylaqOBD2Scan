package com.example.auto

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.ui.screens.DrivingDashboardScreen
import com.example.ui.viewmodel.MainViewModel

/**
 * Sideload-friendly Android Auto / car-launcher entry - the "parked surface activity"
 * pattern (same discovery recipe as the sideloaded AABrowser project).
 *
 * Google's unknown-sources developer toggle unlocks three app classes on real head
 * units: media, messaging and PARKED apps - while template CarAppService apps still
 * require a Play-trusted install. A plain distraction-optimised ACTIVITY declared with
 * CAR_LAUNCHER + NAVIGATION + APP_MAPS categories and the androidx.car.app.ACCESS_SURFACE
 * permission is discovered by the car launcher as a parked/surface app, so a sideloaded
 * APK (GitHub release, adb, Obtainium) CAN reach the head unit through this route.
 *
 * It hosts the same live OBD driving HUD (rpm, speed, gear, converter slip, economy)
 * as the in-app Auto HUD screen. Some hosts grant the car surface only while parked -
 * that is a host safety policy, not an app limitation.
 */
class AutoDashActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            DrivingDashboardScreen(viewModel = viewModel, onBack = { finish() })
        }
    }
}
