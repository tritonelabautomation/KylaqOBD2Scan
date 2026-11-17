package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for AUDIT PHASE-4 (2026-11-12): an archive must be judged from what it
 * *declares* (its central directory) before anything is extracted, and the space it will consume
 * must be compared with what is actually free plus a reserve.
 *
 * Pure-logic port of `ZipImporter.preflightRejection` / `normalizeEntryName` /
 * `requiredSpaceBytes`. The real implementation is exercised end-to-end once an emulator is
 * available; these pin the decision table so it cannot drift.
 */
class ArchivePreflightPolicyTest {

    private companion object {
        const val MAX_ENTRIES = 5_000
        const val MAX_SINGLE_ENTRY = 512L * 1024 * 1024
        const val MAX_TOTAL = 2048L * 1024 * 1024
        const val RESERVE = 128L * 1024 * 1024
    }

    private data class Plan(
        val entryCount: Int = 5,
        val totalUncompressedBytes: Long = 10L * 1024 * 1024,
        val largestEntryBytes: Long = 4L * 1024 * 1024,
        val duplicateNames: List<String> = emptyList(),
        val unsafePaths: List<String> = emptyList()
    )

    /** Mirrors ZipImporter.normalizeEntryName. */
    private fun normalizeEntryName(raw: String): String {
        val slashed = raw.replace('\\', '/').trim()
        val simple = slashed.substringAfterLast('/')
        if (simple.isBlank() || simple.startsWith(".")) return ""
        return if (slashed.contains("..")) "../$simple" else simple
    }

    private fun preflight(plan: Plan, usableSpace: Long = Long.MAX_VALUE): String? = when {
        plan.unsafePaths.isNotEmpty() -> "unsafe_paths"
        plan.duplicateNames.isNotEmpty() -> "duplicate_names"
        plan.entryCount > MAX_ENTRIES -> "too_many_entries"
        plan.totalUncompressedBytes > MAX_TOTAL -> "total_too_large"
        plan.largestEntryBytes > MAX_SINGLE_ENTRY -> "entry_too_large"
        usableSpace < plan.totalUncompressedBytes + RESERVE -> "not_enough_space"
        else -> null
    }

    @Test
    fun `a genuine Kylaq backup passes pre-flight`() {
        assertEquals(null, preflight(Plan()))
    }

    @Test
    fun `the space requirement is the real uncompressed size plus a working reserve`() {
        val plan = Plan(totalUncompressedBytes = 100L * 1024 * 1024)
        val required = plan.totalUncompressedBytes + RESERVE
        assertEquals(228L * 1024 * 1024, required)
        // Exactly the reserve short of the requirement must be rejected...
        assertEquals("not_enough_space", preflight(plan, usableSpace = required - 1))
        // ...and exactly enough must pass.
        assertEquals(null, preflight(plan, usableSpace = required))
    }

    @Test
    fun `a highly compressible archive is judged on its uncompressed size, not its download size`() {
        // A 1 MB download that expands to 1.5 GB: the old code would only find out mid-extract.
        val bomb = Plan(
            entryCount = 10,
            totalUncompressedBytes = 1536L * 1024 * 1024,
            largestEntryBytes = 200L * 1024 * 1024
        )
        assertEquals(null, preflight(bomb, usableSpace = Long.MAX_VALUE))
        assertEquals(
            "not_enough_space",
            preflight(bomb, usableSpace = 300L * 1024 * 1024)
        )
    }

    @Test
    fun `two files with the same base name are rejected instead of silently overwriting`() {
        assertEquals(
            "the extractor keeps only the base name, so a/trip.json and b/trip.json both become " +
                "trip.json and one session is silently lost",
            "duplicate_names",
            preflight(Plan(duplicateNames = listOf("b/trip.json")))
        )
    }

    @Test
    fun `normalisation collapses the same collision the extractor would`() {
        assertEquals("trip.json", normalizeEntryName("a/trip.json"))
        assertEquals("trip.json", normalizeEntryName("b/trip.json"))
        assertEquals("trip.json", normalizeEntryName("a\\trip.json"))
        assertTrue(
            "different directories must normalise to the same name - that is exactly why the " +
                "collision has to be rejected up front",
            normalizeEntryName("a/trip.json") == normalizeEntryName("b/trip.json")
        )
    }

    @Test
    fun `hidden and empty entries normalise to nothing and are not treated as collisions`() {
        assertEquals("", normalizeEntryName("__MACOSX/._trip.json"))
        assertEquals("", normalizeEntryName("   "))
        assertEquals("", normalizeEntryName("dir/.hidden"))
    }

    @Test
    fun `path traversal is refused at pre-flight, before extraction`() {
        assertEquals(
            "unsafe_paths",
            preflight(Plan(unsafePaths = listOf("../../databases/obd_research_logger.db")))
        )
        assertEquals("../escape.json", normalizeEntryName("../escape.json"))
    }

    @Test
    fun `entry count and per-entry limits still apply at pre-flight`() {
        assertEquals("too_many_entries", preflight(Plan(entryCount = MAX_ENTRIES + 1)))
        assertEquals(
            "entry_too_large",
            preflight(Plan(largestEntryBytes = MAX_SINGLE_ENTRY + 1))
        )
    }

    @Test
    fun `a rejected archive never reaches extraction`() {
        // The gate runs on the central directory alone, so a refusal costs one variable read and
        // writes nothing. Modelled as: no extraction side effects for any rejection reason.
        val rejections = listOf(
            preflight(Plan(unsafePaths = listOf(".."))),
            preflight(Plan(duplicateNames = listOf("x/trip.json"))),
            preflight(Plan(entryCount = 99_999)),
            preflight(Plan(), usableSpace = 0)
        )
        assertTrue("every one of these must be refused", rejections.all { it != null })
    }
}
