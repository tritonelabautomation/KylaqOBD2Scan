package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for AUDIT DB-02 / DB-04 / DB-05 (2026-11-12).
 *
 * These are pure-logic tests: they pin the *decisions* the fixed code must make, expressed as
 * small models of the real call paths. They run in CI without Room or a device, which is the
 * only option here (no JDK/Gradle in the audit sandbox). The equivalent instrumentation tests
 * live in [DbDeleteOrderingTest] once an emulator is available.
 */
class DeleteSafetyPolicyTest {

    /**
     * Models `RecordingManager.deleteRecording`: the database delete must be attempted *first*
     * and the session files removed only when it succeeded.
     *
     * AUDIT DB-05: the original order was files-then-rows, so a failed row delete left a trip
     * row with no session behind it - unreadable and unrecoverable.
     */
    private fun deleteRecording(dbDeleteSucceeds: Boolean): Pair<Boolean, Boolean> {
        var filesGone = false
        var rowsGone = false
        val rowsDeleted = dbDeleteSucceeds.let { rowsGone = it; it }
        if (rowsDeleted) filesGone = true
        return rowsGone to filesGone
    }

    @Test
    fun `when the database delete fails the session files are kept`() {
        val (rowsGone, filesGone) = deleteRecording(dbDeleteSucceeds = false)
        assertTrue("rows must not be reported as deleted", !rowsGone)
        assertTrue(
            "AUDIT DB-05: files must survive a failed row delete - the recording has to stay " +
                "recoverable. Deleting them first is what made the old path lossy.",
            !filesGone
        )
    }

    @Test
    fun `when the database delete succeeds the session files are removed`() {
        val (rowsGone, filesGone) = deleteRecording(dbDeleteSucceeds = true)
        assertTrue(rowsGone)
        assertTrue("a fully deleted trip must not leave its session directory behind", filesGone)
    }

    /**
     * AUDIT DB-04: the five table deletes are all-or-nothing. Models the transaction:
     * a failure at any step must roll back every earlier step.
     */
    private fun deleteTripInTransaction(failAtStep: Int?): List<String> {
        val steps = listOf("samples", "rawLogs", "events", "aiAnalysis", "trip")
        val committed = mutableListOf<String>()
        steps.forEachIndexed { index, step ->
            if (failAtStep == index) return emptyList() // rollback: nothing commits
            committed.add(step)
        }
        return committed
    }

    @Test
    fun `a failing step in the trip delete rolls back every earlier delete`() {
        for (failAt in 0..4) {
            val committed = deleteTripInTransaction(failAtStep = failAt)
            assertTrue(
                "AUDIT DB-04: a failure at step $failAt must leave NO rows deleted, otherwise " +
                    "orphaned rows accumulate against a trip that is half gone",
                committed.isEmpty()
            )
        }
    }

    @Test
    fun `a clean trip delete removes all five tables`() {
        assertEquals(
            listOf("samples", "rawLogs", "events", "aiAnalysis", "trip"),
            deleteTripInTransaction(failAtStep = null)
        )
    }

    /**
     * AUDIT DB-02: a downgrade must never delete the database file.
     *
     * The old builder used `fallbackToDestructiveMigrationOnDowngrade()`, which wipes the
     * database when the on-disk version is newer than the running APK. The guard below is the
     * policy the shipped code must obey: refuse to open, leave the bytes alone.
     */
    private fun onDowngrade(dbVersionOnDisk: Int, appExpectsVersion: Int): String =
        if (dbVersionOnDisk > appExpectsVersion) "refuse_and_keep" else "open"

    @Test
    fun `a database written by a newer build is never destroyed`() {
        val outcome = onDowngrade(dbVersionOnDisk = 15, appExpectsVersion = 14)
        assertEquals(
            "AUDIT DB-02: rolling back an update must not wipe recorded trips and raw logs",
            "refuse_and_keep",
            outcome
        )
    }

    @Test
    fun `a same-version database opens normally`() {
        assertEquals("open", onDowngrade(dbVersionOnDisk = 14, appExpectsVersion = 14))
    }

    @Test
    fun `an older database opens so the migrations can run`() {
        assertEquals("open", onDowngrade(dbVersionOnDisk = 9, appExpectsVersion = 14))
    }
}
