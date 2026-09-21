package com.example

import com.example.data.BackupSyncStatus
import com.example.data.RecordTime
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSyncStatusTest {

    @Test
    fun neverWhenNoBackup() {
        assertEquals(BackupSyncStatus.State.NEVER, BackupSyncStatus.forItem(0L, 1_700_000_000_000L))
        assertEquals(BackupSyncStatus.State.NEVER, BackupSyncStatus.forItem(100L, 1_700_000_000_000L))
    }

    @Test
    fun pendingWhenItemNewerThanBackup() {
        val backup = RecordTime.parseStamp("2026-09-21T10:00:00.000+05:30")!!
        val item = backup + 60_000L
        assertEquals(BackupSyncStatus.State.PENDING, BackupSyncStatus.forItem(backup, item))
    }

    @Test
    fun syncedWhenBackupNewerThanItem() {
        val backup = RecordTime.parseStamp("2026-09-21T10:00:00.000+05:30")!!
        val item = backup - 60_000L
        assertEquals(BackupSyncStatus.State.SYNCED, BackupSyncStatus.forItem(backup, item))
    }

    @Test
    fun tripUsesDirMtimeFallback() {
        val backup = RecordTime.parseStamp("2026-09-21T10:00:00.000+05:30")!!
        val start = backup - 3600_000L
        val end = backup - 1000L
        // dir mtime newer than backup -> pending even though end is old (rename case)
        val dirMtime = backup + 10_000L
        assertEquals(BackupSyncStatus.State.PENDING, BackupSyncStatus.forTrip(backup, end, start, dirMtime))
        // dir mtime null -> falls back to end
        assertEquals(BackupSyncStatus.State.SYNCED, BackupSyncStatus.forTrip(backup, end, start, null))
    }
}
