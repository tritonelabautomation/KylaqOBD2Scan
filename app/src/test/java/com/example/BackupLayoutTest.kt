package com.example

import com.example.backup.BackupLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup ZIP layout (2026-09-16). Backups are FLAT - one ZIP with every session's files
 * side by side - so a restore that cannot tell sessions apart loses all but one trip (and
 * could mix session A's JSON with session B's CSV). These tests pin the grouping.
 */
class BackupLayoutTest {

    @Test
    fun `each backup file name is classified`() {
        assertEquals(BackupLayout.Kind.TRIP_JSON, BackupLayout.kindOf("2df90142.json"))
        assertEquals(BackupLayout.Kind.TRANSACTIONS_CSV, BackupLayout.kindOf("2df90142_transactions.csv"))
        assertEquals(BackupLayout.Kind.SAMPLES_CSV, BackupLayout.kindOf("2df90142_samples.csv"))
        assertEquals(BackupLayout.Kind.RAW_LOG_TXT, BackupLayout.kindOf("2df90142_raw.txt"))
        assertEquals(BackupLayout.Kind.BUNDLE_ZIP, BackupLayout.kindOf("2df90142_bundle.zip"))
        assertEquals(BackupLayout.Kind.UNSAVED_RAW_LOG, BackupLayout.kindOf("raw_log_2df90142.txt"))
        assertEquals(BackupLayout.Kind.DATA_SNAPSHOT, BackupLayout.kindOf("app_data_snapshot.json"))
        assertEquals(BackupLayout.Kind.UNKNOWN, BackupLayout.kindOf("transactions.csv"))
        assertEquals(BackupLayout.Kind.UNKNOWN, BackupLayout.kindOf("readme.pdf"))
    }

    @Test
    fun `the snapshot wins over the generic json rule`() {
        // Both end in .json; if the snapshot were treated as a trip it would import a
        // garbage session and could shadow a real trip's metadata.
        assertEquals(BackupLayout.Kind.DATA_SNAPSHOT, BackupLayout.kindOf(BackupLayout.SNAPSHOT_FILE_NAME))
        assertNull(BackupLayout.sessionIdOf(BackupLayout.SNAPSHOT_FILE_NAME))
    }

    @Test
    fun `an unsaved raw log wins over the per-session raw rule`() {
        assertEquals(BackupLayout.Kind.UNSAVED_RAW_LOG, BackupLayout.kindOf("raw_log_x_raw.txt"))
    }

    @Test
    fun `session ids containing underscores survive suffix stripping`() {
        // Ids are recovered by stripping known suffixes, never by splitting on the first
        // underscore - a real test session id is "trip_import_test_123".
        assertEquals("trip_import_test_123", BackupLayout.sessionIdOf("trip_import_test_123.json"))
        assertEquals("trip_import_test_123", BackupLayout.sessionIdOf("trip_import_test_123_transactions.csv"))
        assertEquals("trip_import_test_123", BackupLayout.sessionIdOf("trip_import_test_123_samples.csv"))
        assertEquals("trip_import_test_123", BackupLayout.sessionIdOf("trip_import_test_123_raw.txt"))
        assertEquals("trip_import_test_123", BackupLayout.sessionIdOf("trip_import_test_123_bundle.zip"))
        assertEquals("2df90142", BackupLayout.sessionIdOf("raw_log_2df90142.txt"))
    }

    @Test
    fun `names that carry no session id yield none`() {
        assertNull(BackupLayout.sessionIdOf("transactions.csv"))
        assertNull(BackupLayout.sessionIdOf("readme.pdf"))
        assertNull(BackupLayout.sessionIdOf(""))
    }

    @Test
    fun `a multi-session Drive backup groups every session with its own files`() {
        val names = listOf(
            "aaa.json", "aaa_transactions.csv", "aaa_samples.csv", "aaa_raw.txt", "aaa_bundle.zip",
            "bbb.json", "bbb_transactions.csv",
            "raw_log_ccc.txt",
            "app_data_snapshot.json",
            "notes.txt"
        )
        val groups = BackupLayout.groupBySession(names)

        assertEquals(setOf("aaa", "bbb"), groups.keys)
        assertEquals(5, groups["aaa"]!!.size)
        assertEquals(listOf("bbb.json", "bbb_transactions.csv"), groups["bbb"])
        assertTrue(BackupLayout.isMultiSession(names))
    }

    @Test
    fun `snapshot, unsaved raw logs and unknown files are never treated as a trip`() {
        val names = listOf("app_data_snapshot.json", "raw_log_ccc.txt", "notes.txt")
        assertTrue(BackupLayout.groupBySession(names).isEmpty())
        assertFalse(BackupLayout.isMultiSession(names))
    }

    @Test
    fun `a single-trip export is not multi-session`() {
        val names = listOf("2df90142.json", "2df90142_transactions.csv", "2df90142_raw.txt")
        assertFalse(BackupLayout.isMultiSession(names))
        assertEquals(1, BackupLayout.groupBySession(names).size)
    }

    @Test
    fun `a legacy zip of unnamed files groups under the empty key so the old path still runs`() {
        val names = listOf("log.json", "transactions.csv", "samples.csv")
        val groups = BackupLayout.groupBySession(names)
        // "log.json" carries an id ("log"); the two CSVs do not and stay ungrouped.
        assertEquals(listOf("log.json"), groups["log"])
        assertFalse(groups.containsKey(""))
    }
}
