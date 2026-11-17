package com.example

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for AUDIT IMP-04 (2026-11-12): a ZIP import must be bounded by entry count,
 * per-entry size and available free space - not only by a 2 GB total.
 *
 * Pure-logic models of `ZipImporter.unzipSafely`'s guards. They exist because the original
 * loop had exactly one ceiling, so 100 000 tiny entries or a single 1.9 GB entry were both
 * accepted and only failed later, after the device was already in trouble.
 */
class ZipLimitPolicyTest {

    private companion object {
        const val MAX_ENTRIES = 5_000
        const val MAX_SINGLE_ENTRY = 512L * 1024 * 1024
        const val MAX_TOTAL = 2048L * 1024 * 1024
        const val MIN_FREE = 64L * 1024 * 1024
    }

    /** Mirrors the guard order in `unzipSafely`. Returns null when the entry is accepted. */
    private fun guard(
        entryIndex: Int,
        entryBytes: Long,
        totalSoFar: Long,
        freeSpace: Long = Long.MAX_VALUE
    ): String? {
        if (freeSpace < MIN_FREE) return "free_space"
        if (entryIndex + 1 > MAX_ENTRIES) return "too_many_entries"
        if (entryBytes > MAX_SINGLE_ENTRY) return "entry_too_large"
        if (totalSoFar + entryBytes > MAX_TOTAL) return "total_too_large"
        return null
    }

    @Test
    fun `a normal multi-file backup is accepted`() {
        // 40 files of 500 KB is a realistic month of trips.
        var total = 0L
        repeat(40) { i ->
            val bytes = 500L * 1024
            assertTrue("entry $i should be accepted", guard(i, bytes, total) == null)
            total += bytes
        }
    }

    @Test
    fun `an archive with too many entries is rejected before it can create them all`() {
        val reason = guard(entryIndex = MAX_ENTRIES, entryBytes = 10, totalSoFar = 0)
        assertTrue(
            "AUDIT IMP-04: the 2 GB total alone lets an archive with millions of tiny entries " +
                "create millions of files",
            reason == "too_many_entries"
        )
    }

    @Test
    fun `the last permitted entry is still accepted`() {
        val reason = guard(entryIndex = MAX_ENTRIES - 1, entryBytes = 10, totalSoFar = 0)
        assertTrue("off-by-one must not reject a legitimate archive", reason == null)
    }

    @Test
    fun `a single oversized entry is rejected even though it fits the total budget`() {
        val reason = guard(entryIndex = 0, entryBytes = MAX_SINGLE_ENTRY + 1, totalSoFar = 0)
        assertTrue(
            "AUDIT IMP-04: 1.9 GB in one file passed the 2 GB total check and filled the volume",
            reason == "entry_too_large"
        )
    }

    @Test
    fun `the total ceiling still applies across many acceptable entries`() {
        val reason = guard(
            entryIndex = 10,
            entryBytes = MAX_SINGLE_ENTRY,
            totalSoFar = MAX_TOTAL - 1000
        )
        assertTrue(reason == "total_too_large")
    }

    @Test
    fun `an import is refused before writing when the volume is nearly full`() {
        val reason = guard(
            entryIndex = 0,
            entryBytes = 1024,
            totalSoFar = 0,
            freeSpace = MIN_FREE - 1
        )
        assertTrue(
            "AUDIT IMP-04: this used to surface as an IOException partway through extraction, " +
                "on a device whose storage was already full",
            reason == "free_space"
        )
    }

    @Test
    fun `exactly the minimum free space is enough`() {
        val reason = guard(entryIndex = 0, entryBytes = 1024, totalSoFar = 0, freeSpace = MIN_FREE)
        assertTrue(reason == null)
    }
}
