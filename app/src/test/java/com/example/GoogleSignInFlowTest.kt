package com.example

import com.example.backup.CloudBackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google entry point the owner asked for twice (2026-09-20, with a reference screenshot of
 * another app's flow): *"I asked for such a continue with google then choose which Google account
 * I want to login."*
 *
 * The button wording is part of the promise: "Continue with Google" is what people recognise from
 * every other app, and once an account is connected the SAME button must say it switches accounts
 * - because tapping it signs out first so Google's sheet lists every account on the phone again
 * instead of silently reusing the stored one. A button that kept saying "Continue with Google"
 * while reusing a stored account would take the choice away from him.
 */
class GoogleSignInFlowTest {

    @Test
    fun theButtonSaysContinueWithGoogleBeforeAnyAccount() {
        assertEquals("Continue with Google", CloudBackupManager.continueButtonLabel(null))
        assertEquals("Continue with Google", CloudBackupManager.continueButtonLabel(""))
        assertEquals("Continue with Google", CloudBackupManager.continueButtonLabel("   "))
    }

    @Test
    fun theSameButtonBecomesTheAccountSwitchOnceConnected() {
        assertEquals(
            "Switch Google account",
            CloudBackupManager.continueButtonLabel("jay.tritone@gmail.com")
        )
    }

    @Test
    fun theZeroSetupChooserPathIsWiredInTheManifest() {
        // Owner 2026-09-20, after hitting the OAuth-client wall: "Connection with Google doesn't
        // work." The fallback path lists the phone's Google accounts through the SYSTEM chooser,
        // which needs GET_ACCOUNTS in the manifest and the google account type - pin both so the
        // path cannot be deleted silently.
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.GET_ACCOUNTS"))
        assertEquals("com.google", CloudBackupManager.GOOGLE_ACCOUNT_TYPE)
    }
}
