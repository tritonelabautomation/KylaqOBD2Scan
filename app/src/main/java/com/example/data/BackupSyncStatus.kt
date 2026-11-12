package com.example.data

/**
 * OneDrive-style sync status for every item that rides in the Drive backup ZIP
 * (owner 2026-09-21: "Just beside each log show small icon whether it's synced
 * to cloud or not for everything trips, car pool, fuel logs everything which need
 * to backup to Claude just show status. So, I know it's synced. Like how onedrive
 * files in windows file explorer show status of cloud, local, sync, online etc").
 *
 * The backup is a FULL ZIP (DriveBackupClient.sendBackup): all session dirs +
 * app_data_snapshot.json (fuel, car-pool, expenses, docs, reminders, plans, settings).
 * So "synced" means: this item's last modification instant is <= lastBackupTimestamp.
 *
 * Pure JVM — unit-tested.
 */
object BackupSyncStatus {

    enum class State {
        /** No backup ever taken on this phone. */
        NEVER,
        /** This item changed after the last backup — needs a backup. */
        PENDING,
        /** Last backup is newer than this item — safe in Drive. */
        SYNCED
    }

    /**
     * @param lastBackupMs 0 or < MIN_PLAUSIBLE means never backed up
     * @param itemMs last modification of the item (trip end, fuel idMs, car-pool idMs, etc)
     */
    fun forItem(lastBackupMs: Long, itemMs: Long?): State {
        if (lastBackupMs < RecordTime.MIN_PLAUSIBLE_EPOCH_MS) return State.NEVER
        if (itemMs == null) return State.SYNCED // no timestamp to compare → assume synced
        return if (itemMs > lastBackupMs) State.PENDING else State.SYNCED
    }

    /**
     * Trip sync: uses file lastModified as the truth (session dir mtime), falls back to
     * Room end/start timestamps. The dir mtime changes on rename/merge/finalize, so a
     * merged trip is pending until next backup.
     */
    fun forTrip(
        lastBackupMs: Long,
        endMs: Long?,
        startMs: Long?,
        dirLastModifiedMs: Long?
    ): State {
        if (lastBackupMs < RecordTime.MIN_PLAUSIBLE_EPOCH_MS) return State.NEVER
        val itemMs = dirLastModifiedMs?.takeIf { it >= RecordTime.MIN_PLAUSIBLE_EPOCH_MS }
            ?: endMs?.takeIf { it >= RecordTime.MIN_PLAUSIBLE_EPOCH_MS }
            ?: startMs
        return forItem(lastBackupMs, itemMs)
    }

    /** Human label for the icon. */
    fun label(state: State): String = when (state) {
        State.SYNCED -> "Synced to Drive"
        State.PENDING -> "Local — needs backup"
        State.NEVER -> "Never backed up"
    }
}
