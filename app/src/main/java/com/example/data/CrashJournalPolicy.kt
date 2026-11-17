package com.example.data

/**
 * Pure decision core of the app's own crash journal (owner 2026-09-20: "Again app is
 * crashing when I open it crashes. No app crash logs why it has crashed").
 *
 * Every rule that decides what a crash record says, which file it lands in, when the
 * next launch must open in safe mode, and which two lines of a stack trace become the
 * owner-visible reason lives here so it is unit-tested on the JVM - the file IO around
 * it ([CrashJournal]) is deliberately dumb.
 */
object CrashJournalPolicy {

    /** Consecutive starts that died before drawing their first frame; the next start opens in safe mode. */
    const val SAFE_MODE_AFTER = 2

    /**
     * Characters of a crash record the launch notice will read. The notice shows the exception line
     * plus one app frame; everything past this is for the uploaded issue, not the dialog.
     */
    const val MAX_NOTICE_READ_CHARS = 16_384

    /** Header keys of a crash record; also the parse anchors for [crashedAt]. */
    const val CRASHED_AT_KEY = "crashed_at"
    const val APP_VERSION_KEY = "app_version"
    const val DEVICE_KEY = "device"
    const val THREAD_KEY = "thread"

    fun shouldEnterSafeMode(consecutiveCrashes: Int): Boolean =
        consecutiveCrashes >= SAFE_MODE_AFTER

    /** One file per crash, never overwritten, never deleted by the app. */
    fun fileNameFor(epochMs: Long): String = "crash_$epochMs.txt"

    /** Heap state at the moment of death, e.g. `used=498MB max=512MB (97%) level=CRITICAL`. */
    const val HEAP_KEY = "heap"

    /** Section header for the memory-pressure tail appended after the stack trace. */
    const val PRESSURE_TAIL_KEY = "--- memory pressure log (tail) ---"

    /**
     * What a crash record carries. [heapState] and [pressureTail] are optional so older callers
     * (and older tests) keep working, but they are what turned two unfixable OOM reports
     * (2026-11-16) into evidence: the stack only says WHERE an allocation failed, never how full
     * the heap already was or whether the app had been shedding.
     */
    fun formatRecord(
        istTime: String,
        versionName: String,
        device: String,
        threadName: String,
        stackTrace: String,
        heapState: String? = null,
        pressureTail: String? = null
    ): String = buildString {
        append(CRASHED_AT_KEY).append(": ").append(istTime).append('\n')
        append(APP_VERSION_KEY).append(": ").append(versionName).append('\n')
        append(DEVICE_KEY).append(": ").append(device).append('\n')
        append(THREAD_KEY).append(": ").append(threadName).append('\n')
        if (!heapState.isNullOrBlank()) append(HEAP_KEY).append(": ").append(heapState).append('\n')
        append(stackTrace)
        if (!pressureTail.isNullOrBlank()) {
            append('\n')
            append(PRESSURE_TAIL_KEY).append('\n')
            append(pressureTail.trimEnd()).append('\n')
        }
    }

    /** The IST stamp a record carries, or null when absent/unreadable. */
    fun crashedAt(record: String?): String? {
        record ?: return null
        return record.lineSequence()
            .firstOrNull { it.startsWith("$CRASHED_AT_KEY:") }
            ?.substringAfter("$CRASHED_AT_KEY:")
            ?.trim()
            ?.takeUnless { it.isBlank() }
    }

    /**
     * The owner-readable reason: the DEEPEST "Caused by:" (or the exception line itself
     * when there is no chain) plus the first frame of THIS app's code below that cause -
     * the line that actually threw. Frames above the cause belong to the wrapper, not to
     * the culprit, so the search starts under it and only falls back to the whole trace.
     * Null only when there is no record at all.
     */
    fun summarize(record: String?): String? {
        if (record.isNullOrBlank()) return null
        // The diagnostics section appended after the stack ([PRESSURE_TAIL_KEY] and everything
        // under it) is not a stack frame and must never be blamed for the crash.
        val stackOnly = record.substringBefore("\n$PRESSURE_TAIL_KEY")
        val stackLines = stackOnly.lines().filter { line ->
            !line.startsWith("$CRASHED_AT_KEY:") &&
                !line.startsWith("$APP_VERSION_KEY:") &&
                !line.startsWith("$DEVICE_KEY:") &&
                !line.startsWith("$THREAD_KEY:") &&
                !line.startsWith("$HEAP_KEY:")
        }
        val causeIndex = stackLines.indexOfLast { it.startsWith("Caused by:") }
        val cause = if (causeIndex >= 0) {
            stackLines[causeIndex]
        } else {
            stackLines.firstOrNull { it.isNotBlank() && !it.trim().startsWith("at ") } ?: return null
        }
        val searchFrom = if (causeIndex >= 0) causeIndex + 1 else 0
        val appFrame = (
            stackLines.drop(searchFrom).firstOrNull { it.trim().startsWith("at com.example") }
                ?: stackLines.firstOrNull { it.trim().startsWith("at com.example") }
            )?.trim()?.removePrefix("at ")
        return if (appFrame != null && appFrame.isNotBlank()) "$cause\n$appFrame" else cause
    }
}
