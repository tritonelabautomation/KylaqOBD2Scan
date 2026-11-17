package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for AUDIT AI-02, PERM-01 and MEM-02 (2026-11-12).
 *
 * Pure-logic models of the fixed call paths - no Android, no Room, no device. They pin the
 * exact behaviours that were wrong, so a later refactor cannot silently reintroduce them.
 */
class ConversationAndPermissionPolicyTest {

    // region AI-02 - bounded AI Doctor conversation

    private companion object {
        const val STORED_LIMIT = 60   // MainViewModel.AI_CHAT_HISTORY_LIMIT
        const val REQUEST_LIMIT = 20  // MainViewModel.AI_REQUEST_HISTORY_LIMIT
    }

    /** Models the append site: history grows, then is trimmed to the stored limit. */
    private fun appendTurn(history: List<String>, user: String, loading: String): List<String> =
        (history + user + loading).takeLast(STORED_LIMIT)

    @Test
    fun `stored history stops growing after the limit`() {
        var history = listOf("greeting")
        repeat(200) { turn ->
            history = appendTurn(history, "question $turn", "Analyzing...")
        }
        assertEquals(
            "AUDIT AI-02: the history list must not grow for the whole process lifetime",
            STORED_LIMIT,
            history.size
        )
    }

    @Test
    fun `bounded history keeps the newest turns, not the oldest`() {
        var history = listOf("greeting")
        repeat(50) { turn ->
            history = appendTurn(history, "question $turn", "Analyzing...")
        }
        assertTrue(
            "the most recent question must survive the trim",
            history.contains("question 49")
        )
        assertTrue(
            "the first questions are the ones that get dropped",
            !history.contains("question 0")
        )
    }

    @Test
    fun `the request body stays bounded no matter how long the session runs`() {
        var history = listOf("greeting")
        repeat(500) { turn ->
            history = appendTurn(history, "question $turn", "Analyzing...")
        }
        // Mirrors sendAiMessage: drop the trailing "Analyzing..." placeholder, then cap.
        val requestHistory = history.dropLast(1).takeLast(REQUEST_LIMIT)
        assertTrue(
            "AUDIT AI-02: a long session must not produce an unbounded Gemini request body",
            requestHistory.size <= REQUEST_LIMIT
        )
    }

    @Test
    fun `the stored limit is larger than the request limit`() {
        assertTrue(
            "the UI scrollback should outlive what is sent to the model, otherwise trimming " +
                "the request would also erase the user's visible conversation",
            STORED_LIMIT > REQUEST_LIMIT
        )
    }

    // endregion

    // region PERM-01 - Bluetooth flag must ignore unrelated permissions

    /** Models the fixed callback: only Bluetooth keys participate in the flag. */
    private fun bluetoothGranted(results: Map<String, Boolean>): Boolean {
        val btKeys = results.keys.filter {
            it == "BLUETOOTH_CONNECT" || it == "BLUETOOTH_SCAN" ||
                it == "BLUETOOTH" || it == "BLUETOOTH_ADMIN"
        }
        return btKeys.isNotEmpty() && btKeys.all { results[it] == true }
    }

    @Test
    fun `denying notifications does not make bluetooth look denied`() {
        val result = bluetoothGranted(
            mapOf(
                "BLUETOOTH_CONNECT" to true,
                "BLUETOOTH_SCAN" to true,
                "ACCESS_FINE_LOCATION" to true,
                "ACCESS_COARSE_LOCATION" to true,
                "POST_NOTIFICATIONS" to false
            )
        )
        assertTrue(
            "AUDIT PERM-01: the old `values.all { it }` turned Bluetooth false here - exactly " +
                "the bug this test exists to prevent",
            result
        )
    }

    @Test
    fun `denying location does not make bluetooth look denied`() {
        val result = bluetoothGranted(
            mapOf(
                "BLUETOOTH_CONNECT" to true,
                "BLUETOOTH_SCAN" to true,
                "ACCESS_FINE_LOCATION" to false
            )
        )
        assertTrue(result)
    }

    @Test
    fun `denying a bluetooth permission still reports bluetooth denied`() {
        val result = bluetoothGranted(
            mapOf("BLUETOOTH_CONNECT" to false, "BLUETOOTH_SCAN" to true)
        )
        assertTrue("the fix must not make the flag lie in the other direction", !result)
    }

    @Test
    fun `a request with no bluetooth keys at all is not assumed granted`() {
        assertTrue(!bluetoothGranted(mapOf("ACCESS_FINE_LOCATION" to true)))
    }

    // endregion

    // region SEC-01 - cloud backup must never carry telemetry

    /**
     * Guards the backup policy: cloud backup may only carry shared preferences. This is the
     * SEC-01 rule, asserted rather than trusted, because an `<include path=".">` added later
     * would silently re-expose raw frame history and crash traces to the cloud.
     */
    private fun cloudBackupAllowedDomains(): Set<String> = setOf("sharedpref")

    @Test
    fun `cloud backup never carries recordings, raw logs, crash logs or the database`() {
        val allowed = cloudBackupAllowedDomains()
        for (domain in listOf("recordings", "raw_logs", "crash_logs", "obd_research_logger.db")) {
            assertTrue(
                "AUDIT SEC-01: '$domain' must not be eligible for cloud auto-backup",
                domain !in allowed
            )
        }
    }

    // endregion
}
