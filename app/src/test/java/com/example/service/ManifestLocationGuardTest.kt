package com.example.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-09-18 altitude defect, pinned at the manifest so it cannot regress silently.
 *
 * A 93-minute pocketed-phone drive came back with a blank altitude column while a 35-minute drive
 * with the app on screen recorded a full 387-440 m trace. The difference was not the permission the
 * owner had granted - it was that the recording service declared foregroundServiceType
 * "connectedDevice" only. Android counts location access made while a LOCATION-type foreground
 * service runs as while-in-use access; a service without that type is "background" for location
 * purposes, and Android 10+ hands it zero fixes once the screen goes off.
 *
 * These assertions read the manifest as text on purpose: the failure mode they guard is a manifest
 * attribute, invisible to every behavioural test in the suite.
 */
class ManifestLocationGuardTest {

    private val manifest: String by lazy {
        listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .firstNotNullOfOrNull { path -> File(path).takeIf { it.exists() } }
            ?.readText()
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
    }

    @Test
    fun theRecordingServiceIsALocationTypeForegroundService() {
        assertTrue(
            "ObdKeepAliveService must declare the location type or pocketed drives lose GPS: $manifest",
            manifest.contains("android:foregroundServiceType=\"connectedDevice|location\"")
        )
    }

    @Test
    fun theLocationForegroundServicePermissionIsDeclared() {
        // Android 14+ throws at startForeground(FOREGROUND_SERVICE_TYPE_LOCATION) without it.
        assertTrue(
            manifest.contains("android.permission.FOREGROUND_SERVICE_LOCATION")
        )
    }

    @Test
    fun theBackgroundPermissionRemainsAsBelt() {
        // Still needed for the one case the location type cannot cover: a service that has to
        // (re)start with no activity visible.
        assertTrue(
            manifest.contains("android.permission.ACCESS_BACKGROUND_LOCATION")
        )
    }

    @Test
    fun startForegroundUsesTheLocationTypeInTheCode() {
        val service = listOf(
            "src/main/java/com/example/service/ObdKeepAliveService.kt",
            "app/src/main/java/com/example/service/ObdKeepAliveService.kt"
        ).firstNotNullOfOrNull { path -> File(path).takeIf { it.exists() } }?.readText().orEmpty()
        assertTrue(
            "the manifest type alone does nothing unless startForeground passes it",
            service.contains("FOREGROUND_SERVICE_TYPE_LOCATION")
        )
    }
}
