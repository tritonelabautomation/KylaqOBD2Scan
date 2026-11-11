package com.example

import com.example.update.UpdateInstallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PackageInstaller status mapping (owner 2026-11-10: ColorOS sheet ended on
 * "App not installed" with no reason). The installer codes must never leak as
 * a blank or generic string — Settings has to say *why* so a signature clash
 * is not retried as another doomed Download & install.
 */
class UpdateInstallStatusTest {

    @Test
    fun `codes match android content pm PackageInstaller`() {
        // Locked to the framework constants so a typo cannot send ColorOS's
        // "App not installed" back into a silent success path.
        assertEquals(-1, UpdateInstallStatus.PENDING_USER_ACTION)
        assertEquals(0, UpdateInstallStatus.SUCCESS)
        assertEquals(1, UpdateInstallStatus.FAILURE)
        assertEquals(2, UpdateInstallStatus.FAILURE_BLOCKED)
        assertEquals(3, UpdateInstallStatus.FAILURE_ABORTED)
        assertEquals(4, UpdateInstallStatus.FAILURE_INVALID)
        assertEquals(5, UpdateInstallStatus.FAILURE_CONFLICT)
        assertEquals(6, UpdateInstallStatus.FAILURE_STORAGE)
        assertEquals(7, UpdateInstallStatus.FAILURE_INCOMPATIBLE)
    }

    @Test
    fun `success and pending confirmation are silent`() {
        assertNull(UpdateInstallStatus.userMessage(UpdateInstallStatus.PENDING_USER_ACTION))
        assertNull(UpdateInstallStatus.userMessage(UpdateInstallStatus.SUCCESS, "ok"))
    }

    @Test
    fun `user abort is silent so cancel is not an error`() {
        assertNull(UpdateInstallStatus.userMessage(UpdateInstallStatus.FAILURE_ABORTED, "aborted"))
    }

    @Test
    fun `incompatible names the signing-key and versionCode case`() {
        val msg = UpdateInstallStatus.userMessage(
            UpdateInstallStatus.FAILURE_INCOMPATIBLE,
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
        )
        assertTrue(msg != null)
        assertTrue(msg!!.contains("signing-key"))
        assertTrue(msg.contains("versionCode"))
        assertTrue(msg.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertTrue(msg.contains("uninstall"))
    }

    @Test
    fun `invalid points at a bad download not a signature clash`() {
        val msg = UpdateInstallStatus.userMessage(UpdateInstallStatus.FAILURE_INVALID, null)
        assertTrue(msg != null)
        assertTrue(msg!!.contains("could not read", ignoreCase = true))
        assertTrue(msg.contains("Download & install"))
    }

    @Test
    fun `blocked storage conflict and generic failure stay specific`() {
        assertTrue(
            UpdateInstallStatus.userMessage(UpdateInstallStatus.FAILURE_BLOCKED)!!
                .contains("blocked", ignoreCase = true)
        )
        assertTrue(
            UpdateInstallStatus.userMessage(UpdateInstallStatus.FAILURE_STORAGE)!!
                .contains("storage", ignoreCase = true)
        )
        assertTrue(
            UpdateInstallStatus.userMessage(UpdateInstallStatus.FAILURE_CONFLICT)!!
                .contains("conflicts", ignoreCase = true)
        )
        assertEquals(
            "Install failed.",
            UpdateInstallStatus.userMessage(UpdateInstallStatus.FAILURE)
        )
    }

    @Test
    fun `unknown status codes still produce a message with the number`() {
        val msg = UpdateInstallStatus.userMessage(42, "mystery")
        assertTrue(msg != null)
        assertTrue(msg!!.contains("42"))
        assertTrue(msg.contains("mystery"))
    }

    @Test
    fun `blank statusMessage is omitted rather than leaving empty parens`() {
        assertEquals(
            "Install failed.",
            UpdateInstallStatus.userMessage(UpdateInstallStatus.FAILURE, "   ")
        )
    }
}
