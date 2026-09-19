package com.example

import com.example.service.KeepAlivePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restart decisions of the boot/update receiver (KILL-AUDIT FIX D, owner 2026-09-19:
 * "At any cost kylaq TSI coach app shouldn't be killed by android ... find hidden mechanism
 * which could kill app during trip and lose a trip data which I don't want").
 *
 * A reboot or an in-app update kills the process with a possibly half-written journal on disk.
 * These rules decide when the keep-alive service is revived to rescue it - and when it must NOT
 * be, so a phone with nothing to log does not grow a permanent notification after every boot.
 */
class KeepAlivePolicyTest {

    @Test
    fun onlyBootAndPackageReplaceMayReviveTheService() {
        assertTrue(KeepAlivePolicy.isStartBroadcast("android.intent.action.BOOT_COMPLETED"))
        assertTrue(KeepAlivePolicy.isStartBroadcast("android.intent.action.MY_PACKAGE_REPLACED"))
        // The system broadcasts these to EVERY package - only OUR replacement is a rescue.
        assertFalse(KeepAlivePolicy.isStartBroadcast("android.intent.action.PACKAGE_REPLACED"))
        assertFalse(KeepAlivePolicy.isStartBroadcast("android.intent.action.PACKAGE_ADDED"))
        assertFalse(KeepAlivePolicy.isStartBroadcast("android.intent.action.SCREEN_ON"))
        assertFalse(KeepAlivePolicy.isStartBroadcast(null))
        assertFalse(KeepAlivePolicy.isStartBroadcast(""))
    }

    @Test
    fun aCutOffJournalIsDetectedFromTheDirectoryListingAlone() {
        val sizes = mapOf(
            "s1_transactions.csv" to 5_000L, // rows on disk, process died mid-drive
            "s1_samples.csv" to 9_000L,
            "s1.meta" to 300L,
            "s2_transactions.csv" to 5_000L, // finished cleanly - marker present
            "s2.finished" to 80L,
            "s3_transactions.csv" to 60L // header only: never got an ECU response
        )
        val finished = { id: String -> sizes.containsKey("$id.finished") }
        val pending = KeepAlivePolicy.hasPendingJournal(
            fileNames = sizes.keys.toList(),
            sizeOfBytes = { name -> sizes[name] ?: 0L },
            isFinished = finished
        )
        assertTrue("s1 was cut off and must be rescued", pending)

        // Same directory after s1 is recovered: nothing pending any more.
        val after = sizes.filterKeys { !it.startsWith("s1") }
        assertFalse(
            KeepAlivePolicy.hasPendingJournal(
                fileNames = after.keys.toList(),
                sizeOfBytes = { name -> after[name] ?: 0L },
                isFinished = finished
            )
        )
        // Empty or unrelated directories never start anything.
        assertFalse(
            KeepAlivePolicy.hasPendingJournal(emptyList(), { 0L }, { false })
        )
        assertFalse(
            KeepAlivePolicy.hasPendingJournal(
                fileNames = listOf("notes.txt"),
                sizeOfBytes = { 999L },
                isFinished = { false }
            )
        )
    }

    @Test
    fun serviceStartsForARescueOrForAutoConnectButNotForNothing() {
        assertTrue(KeepAlivePolicy.shouldStartKeepAlive(pendingJournal = true, autoConnectEnabled = false))
        assertTrue(KeepAlivePolicy.shouldStartKeepAlive(pendingJournal = false, autoConnectEnabled = true))
        assertTrue(KeepAlivePolicy.shouldStartKeepAlive(pendingJournal = true, autoConnectEnabled = true))
        assertFalse(
            "nothing to rescue and auto-connect off: a permanent notification would be noise",
            KeepAlivePolicy.shouldStartKeepAlive(pendingJournal = false, autoConnectEnabled = false)
        )
    }

    @Test
    fun theActionConstantsMatchTheManifestFilters() {
        // The receiver's manifest intent-filter and the policy must never drift apart.
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains(KeepAlivePolicy.ACTION_BOOT_COMPLETED))
        assertTrue(manifest.contains(KeepAlivePolicy.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(manifest.contains("com.example.service.KeepAliveBootReceiver") ||
            manifest.contains(".service.KeepAliveBootReceiver"))
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        // Declared exactly once as a <receiver> element (the comment beside the permission also
        // names it, so count the attribute, not the bare word).
        assertEquals(1, manifest.split("android:name=\".service.KeepAliveBootReceiver\"").size - 1)
    }
}
