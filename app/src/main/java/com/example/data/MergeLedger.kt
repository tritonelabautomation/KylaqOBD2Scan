package com.example.data

import java.io.File

/**
 * The tombstone list of session ids that a merge has already consumed.
 *
 * Owner, 2026-09-22: *"When I merge fragmented same trip it's showing as recovered and merging
 * multiple times."*
 *
 * That loop was mechanical, not a display bug. `mergeSessions` deleted the fragments' session
 * directories, their Room rows and their crash journals - but their raw ELM logs stayed where
 * every recording leaves them, `files/raw_logs/raw_log_<sessionId>.txt`. Recovery decides what
 * to rebuild with one test: *is this session id already a saved trip?* After a merge the answer
 * is NO for every fragment, because the merged trip carries a NEW id. So the next launch's
 * automatic recovery pass rebuilt each fragment from its raw log, named it "Recovered Run ...",
 * and the owner was looking at the same three pieces he had just merged - which he then merged
 * again, each pass folding another copy of the same drive into the trip.
 *
 * Two fixes, both needed:
 *
 *  1. the merge now folds the fragments' raw logs INTO the merged session bundle and removes
 *     them from the recovery scan directory, so there is nothing left to rebuild from;
 *  2. this ledger records every id a merge consumed. Recovery - which also runs from the
 *     keep-alive service, from a boot receiver and from a Drive restore, none of which can be
 *     trusted to see fix 1 - refuses to resurrect a listed id. It is the difference between
 *     "the files are gone" and "the app remembers".
 *
 * Append-only text, one `mergedAwayId|intoId|epochMillis` line per consumed fragment: readable
 * with a file manager, tolerant of a truncated last line, and cheap enough to read on every
 * recovery pass.
 */
class MergeLedger(private val file: File) {

    data class Entry(
        val mergedAwayId: String,
        val intoId: String,
        val atMs: Long
    ) {
        fun toLine(): String = "$mergedAwayId|$intoId|$atMs"
    }

    /** Parses ledger text. A malformed line is skipped, never fatal - this is a safety net. */
    fun parse(text: String): List<Entry> =
        text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split('|')
            if (parts.size < 2) return@mapNotNull null
            val id = parts[0].trim()
            if (id.isBlank()) return@mapNotNull null
            Entry(
                mergedAwayId = id,
                intoId = parts[1].trim(),
                atMs = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
            )
        }.toList()

    fun entries(): List<Entry> =
        runCatching { if (file.isFile) parse(file.readText()) else emptyList<Entry>() }
            .getOrDefault(emptyList<Entry>())

    /** Every id a merge has consumed. */
    fun ids(): Set<String> = entries().map { it.mergedAwayId }.toSet()

    fun contains(sessionId: String): Boolean = sessionId in ids()

    /**
     * Records a merge. Idempotent by id: re-recording an id keeps the FIRST entry, so a repeated
     * merge attempt cannot rewrite history and make a fragment look newer than it is.
     */
    fun record(mergedAwayIds: Collection<String>, intoId: String, atMs: Long = System.currentTimeMillis()) {
        val known = entries()
        val already = known.map { it.mergedAwayId }.toSet()
        val fresh = mergedAwayIds
            .distinct()
            .filter { it.isNotBlank() && it !in already }
            .map { Entry(it, intoId, atMs) }
        if (fresh.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(fresh.joinToString("\n") { it.toLine() } + "\n")
        }.onFailure { e ->
            // Wrapped: a tombstone that cannot be written must not take the merge down with it -
            // the fragments are deleted below either way - but it must not be silent either.
            runCatching { android.util.Log.e("MergeLedger", "could not record merged sessions", e) }
        }
    }

    /**
     * Drops entries whose merged trip no longer exists. Called when the owner deletes a trip:
     * keeping a tombstone for a live trip would be a lie, and keeping one for a DELETED merged
     * trip would permanently forbid recovering the fragments he may still have on disk.
     */
    fun retainOnly(liveMergedIds: Set<String>) {
        val kept = entries().filter { it.intoId in liveMergedIds }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") { it.toLine() } + "\n")
        }.onFailure { e ->
            // A rewrite that fails leaves the OLD tombstones in place, which errs towards refusing
            // to resurrect a fragment - the safe direction. Still not silent.
            runCatching { android.util.Log.e("MergeLedger", "could not prune merged sessions", e) }
        }
    }
}
