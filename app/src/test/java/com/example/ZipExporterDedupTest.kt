package com.example

import com.example.data.ZipExporter
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The zip safety net (owner 2026-09-21: "Backup failed: duplicate entry:
 * 096b9a42_transactions.csv", "Last synchronized: Never"). Entries are flat file names,
 * and the journal working dir plus a finalized session dir both carry
 * "<id>_transactions.csv", so an unfiltered walk handed the zip one name twice and
 * putNextEntry threw - killing every Drive backup since the journal shipped. The walk
 * now excludes the journal dir; this test pins the belt: a duplicate name in ANY future
 * file list skips the second copy instead of destroying the whole backup.
 */
class ZipExporterDedupTest {

    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun duplicateNamesSkipTheSecondCopyInsteadOfKillingTheBackup() {
        val a = dir.newFolder("session_a")
        val j = dir.newFolder("journal")
        val sessionCopy = java.io.File(a, "deadbeef_transactions.csv")
        val journalCopy = java.io.File(j, "deadbeef_transactions.csv")
        sessionCopy.writeText("session,ts,mono\nrow-from-session\n")
        journalCopy.writeText("session,ts,mono\nrow-from-journal\n")
        val unique = dir.newFile("raw_log_deadbeef.txt")
        unique.writeText("raw frames\n")

        val zip = dir.newFile("backup.zip")
        ZipExporter.createTripZip(zip, listOf(sessionCopy, journalCopy, unique))

        ZipFile(zip).use { zf ->
            val names = zf.entries().toList().map { it.name }
            assertEquals("one entry per name, no death", 2, names.size)
            assertTrue(names.contains("deadbeef_transactions.csv"))
            assertTrue(names.contains("raw_log_deadbeef.txt"))
            val kept = zf.getInputStream(zf.getEntry("deadbeef_transactions.csv")).readBytes()
            assertEquals("first copy wins", "session,ts,mono\nrow-from-session\n", String(kept))
        }
    }
}
