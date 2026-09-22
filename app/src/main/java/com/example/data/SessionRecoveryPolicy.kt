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

    /**
     * The true instant of one recorded row. The transports stamp `timestampMonotonic` with
     * SystemClock.elapsedRealtime() - milliseconds since BOOT, not an instant - while
     * `timestampUtc` carries the real IST stamp, so the stamp is the only honest wall clock
     * and the uptime value is a fallback for rows written without one. Reading uptime as an
     * epoch is how every trip's Room window landed around 1970, which made a same-day
     * recovered drive get the "recorded by an older build" altitude footnote
     * (owner 2026-09-20: "Altitude still not logging in").
     */
    fun wallEpochMs(stamp: String?, monotonicMs: Long): Long =
        RecordTime.parseMillis(stamp) ?: monotonicMs

    /**
     * How long a cut-off journal stays RESUMABLE instead of being recovered into its own
     * trip (owner 2026-09-21: a 31 km drive came back as three fragments because every
     * process death mid-drive finalized the live journal as a separate "Recovered Run"
     * and the restart opened a brand-new session). Inside this window a restart APPENDS
     * to the same session; past it, the drive is genuinely over and recovery finalizes.
     */
    const val RESUME_WINDOW_MS: Long = 15 * 60_000L

    /** True while a cut-off journal is fresh enough to resume rather than recover. */
    fun isResumable(lastRowAgeMs: Long, windowMs: Long = RESUME_WINDOW_MS): Boolean =
        lastRowAgeMs in 0L..windowMs

    /**
     * How far a row may sit from a recorded GPS fix and still be given that fix's altitude.
     *
     * GPS delivers at 1 Hz; the poller writes ~11 rows per second. So the fix that was current
     * when a row was stamped is at most ~1 s away, and 1.5 s leaves room for a jittery provider
     * without ever reaching across a real gap. Past this the row keeps null: an elevation from
     * ten minutes ago is not this row's elevation, and a blank is honest where a guess is not.
     */
    const val ALTITUDE_MATCH_TOLERANCE_MS: Long = 1500L

    /**
     * Re-associates a trip's OWN recorded GPS fixes with rows that lost their altitude stamp.
     *
     * Added 2026-09-22 (owner: *"Altitude not logging still"*). Merging reads each fragment's
     * `<id>_transactions.csv`, and until that file grew an `altitude_m` column it held no
     * elevation at all - while the fragment's samples CSV and its Room rows, written from the
     * same drive, held the real fixes. Stitching the fragments together therefore produced a
     * merged trip with a blank altitude trend even though every piece had recorded elevation.
     *
     * This is not interpolation and not invention: the value written is one the recorder measured
     * during that same fragment, at an instant within [toleranceMs] of the row. Rows that already
     * carry an altitude are never touched, rows with no fix nearby stay null, and nothing is
     * extrapolated past the first or last fix.
     *
     * Two-pointer over two time-sorted lists, so a 45 000-row fragment costs one pass rather than
     * a nearest-neighbour search per row - the same trip that once OOM'd the app at 256 MB.
     *
     * @param rows         the fragment's transactions, in any order
     * @param timestamp    each row's wall instant
     * @param hasAltitude  true when the row already carries an altitude and must be left alone
     * @param fixes        (instant, altitude) the fragment recorded, in any order
     * @param withAltitude how to write a fix onto a row
     */
    fun <T> attachAltitude(
        rows: List<T>,
        timestamp: (T) -> Long,
        hasAltitude: (T) -> Boolean,
        fixes: List<Pair<Long, Double>>,
        withAltitude: (T, Double) -> T,
        toleranceMs: Long = ALTITUDE_MATCH_TOLERANCE_MS
    ): List<T> {
        if (rows.isEmpty() || fixes.isEmpty()) return rows
        val sortedFixes = fixes.sortedBy { it.first }
        val out = rows.toMutableList()
        var j = 0
        for (i in rows.indices.sortedBy { timestamp(rows[it]) }) {
            if (hasAltitude(rows[i])) continue
            val ts = timestamp(rows[i])
            while (j + 1 < sortedFixes.size &&
                kotlin.math.abs(sortedFixes[j + 1].first - ts) <= kotlin.math.abs(sortedFixes[j].first - ts)
            ) {
                j++
            }
            val (fixTs, alt) = sortedFixes[j]
            if (kotlin.math.abs(fixTs - ts) <= toleranceMs) out[i] = withAltitude(rows[i], alt)
        }
        return out
    }
}
