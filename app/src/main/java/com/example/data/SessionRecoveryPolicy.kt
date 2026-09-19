package com.example.data

/**
 * The decisions behind crash recovery, extracted so they are testable on the JVM.
 *
 * [RecordingManager] needs an Android `Context` - its files, its Room database - so a unit test
 * cannot construct one. Everything that actually decides *what* to recover and *from where* lives
 * here instead: pure functions over counts, sizes and flags, with the owner's failure mode written
 * into each rule.
 *
 * Owner, 2026-09-17: *"again today logs not saved unable to recover it why the hell kylaq TSI coach
 * app is killed in background it supposed to be service right even in background all ways it should
 * run and record it never ever loose the logs."*
 */
object SessionRecoveryPolicy {

    /** Where a recovered session's transactions should come from. */
    enum class RecoverySource {
        /** The append-only journal: decoded rows, flushed per line, survives a kill. */
        JOURNAL,
        /** The raw ELM log: every frame that reached the phone, but it must be re-parsed. */
        RAW_LOG,
        /** Nothing on disk holds decodable data - report the loss instead of inventing a trip. */
        NONE
    }

    /**
     * When the `.finished` marker may be written (KILL-AUDIT FIX A, owner 2026-09-19: "find hidden
     * mechanism which could kill app during trip and lose a trip data").
     *
     * ONLY after the trip itself is persisted. A marker written before finalization is a lie the
     * recovery pass believes - it filters finished journals out, so a kill inside the finalize
     * window (CSVs, ZIP, Room rows, analyses: seconds of work) lost the whole drive with a
     * clean-stop alibi. No persisted trip -> no marker -> journal stays unfinished -> recovered.
     */
    fun finishedMarkerAllowed(tripPersisted: Boolean): Boolean = tripPersisted

    /**
     * A journal with no `.finished` marker is a session the process died during.
     *
     * The marker is written only by a clean `stopRecording()`, so its absence is the signal - there
     * is no flag to forget to set and no state to lose along with the process.
     */
    fun isUnfinished(hasJournal: Boolean, hasFinishedMarker: Boolean): Boolean =
        hasJournal && !hasFinishedMarker

    /**
     * Which source to rebuild from.
     *
     * The journal wins whenever it holds rows: it carries the DECODED transactions - parameter,
     * value, unit, status and the per-line GPS altitude - so nothing has to be guessed again. The
     * raw log is the fallback for sessions recorded by a build with no journal, or when the journal
     * could not be opened (storage full, directory unwritable). Neither holding data is reported as
     * [RecoverySource.NONE]: the owner gets told a session was lost rather than being handed an
     * empty trip that looks like a successful recovery.
     */
    fun chooseSource(journalRowCount: Int, rawLogBytes: Long): RecoverySource = when {
        journalRowCount > 0 -> RecoverySource.JOURNAL
        rawLogBytes > MIN_RAW_LOG_BYTES -> RecoverySource.RAW_LOG
        else -> RecoverySource.NONE
    }

    /**
     * Which list of transactions to persist at STOP: the one in RAM, or the one in the journal.
     *
     * They differ when the process was restarted mid-drive - auto-reconnect after a kill starts a
     * new process that only ever saw the rows recorded since it came up, while the journal still
     * holds every row of the whole drive. Taking the longer list keeps the drive whole instead of
     * silently truncating it to whatever survived in memory. Equal lengths keep RAM, which carries
     * the fields the CSV round trip drops (per-line altitude, ELM command text).
     */
    fun <T> preferLonger(fromRam: List<T>, fromJournal: List<T>): List<T> =
        if (fromJournal.size > fromRam.size) fromJournal else fromRam

    /**
     * Replay the wide sample rows from the journaled transactions.
     *
     * Used when the samples journal is missing or shorter than the transaction journal - an older
     * build, or a write that failed. [merge] is [RecordingManager.mergeSample]: the SAME pure fold
     * the live recorder applies, so a recovered trip gets the rows STOP would have written rather
     * than an approximation of them.
     */
    fun <S, T> replaySamples(
        transactions: List<T>,
        seed: S,
        merge: (S, T) -> S
    ): List<S> {
        var acc = seed
        val out = ArrayList<S>(transactions.size)
        for (tx in transactions) {
            acc = merge(acc, tx)
            out.add(acc)
        }
        return out
    }

    /** True when the samples journal is usable as-is instead of being replayed. */
    fun samplesUsable(sampleRowCount: Int, transactionRowCount: Int): Boolean =
        sampleRowCount >= transactionRowCount && transactionRowCount > 0

    /**
     * A raw log at or below this size holds only its session header line, so there is no frame
     * inside to rebuild. Same floor `findUnsavedRawLogs` has always used, now named.
     */
    const val MIN_RAW_LOG_BYTES: Long = 64L

    /**
     * A journal file at or below this size cannot hold a data row, so it is not even worth opening.
     *
     * Deliberately NOT the real test: the transactions header alone is 146 characters, so a byte
     * threshold big enough to admit one row would also admit a header-only file, and a session that
     * never received a single ECU response would be offered for "recovery" as an empty trip. The
     * real test is [hasDataRow], which looks for a second line.
     */
    const val MIN_JOURNAL_BYTES: Long = 64L

    /**
     * True when a journal (or any CSV) holds at least one row under its header.
     *
     * Ignition off and the adapter asleep produces a journal with a header and nothing else; that
     * is not a drive, and rebuilding it would create a zero-line trip that looks exactly like a
     * successful recovery of a drive that never happened.
     */
    fun hasDataRow(fileSizeBytes: Long, lineCount: Int): Boolean =
        fileSizeBytes > MIN_JOURNAL_BYTES && lineCount >= 2

    /**
     * Whether the automatic pass should announce anything at all.
     *
     * Silence is correct when nothing was pending - a notice on every launch would train the owner
     * to ignore the one that matters.
     */
    fun shouldAnnounce(recoveredSessions: Int, lostSessions: Int, failures: Int): Boolean =
        recoveredSessions > 0 || lostSessions > 0 || failures > 0
}
