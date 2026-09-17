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
     * A journal file smaller than this holds only its header row: the session never received a
     * single OBD response, so there is no drive inside to rebuild and no reason to report one.
     */
    const val MIN_RAW_LOG_BYTES: Long = 96L

    /**
     * Whether the automatic pass should announce anything at all.
     *
     * Silence is correct when nothing was pending - a notice on every launch would train the owner
     * to ignore the one that matters.
     */
    fun shouldAnnounce(recoveredSessions: Int, lostSessions: Int, failures: Int): Boolean =
        recoveredSessions > 0 || lostSessions > 0 || failures > 0
}
