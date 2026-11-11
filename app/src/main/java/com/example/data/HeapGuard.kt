package com.example.data

/**
 * Hard caps so a long drive or a killed-session recovery cannot fill the ART heap.
 *
 * Owner crash 2026-11-11 19:02 IST on 1.0.832: `OutOfMemoryError` at 512 MB
 * (`largeHeap`) inside `ObdKeepAliveService.onCreate` while recovery materialised
 * every journal row, every Room sample, and the AI-doctor copy of the same list.
 * Peak RAM for those paths must stay a handful of rows, never the whole trip.
 */
object HeapGuard {
    /** Live HUD / monitor ring. The on-disk journal is the full drive. */
    const val LIVE_RAM_ROWS = 200

    /** Room `insertSamples` batch. One chunk in RAM at a time. */
    const val ROOM_INSERT_CHUNK = 200

    /** AI doctor downsamples a trip larger than this instead of loading every row. */
    const val AI_ANALYSIS_MAX_SAMPLES = 12_000

    /**
     * Compose / trip cards. Loading every Room row of a recovered drive into
     * `var samples` is what filled 512 MB on 1.0.842 (Fuel screen + receipt OCR
     * on top of trip 17c54147). Charts and summaries use this cap; the CSV is
     * still the full journal.
     */
    const val UI_SAMPLE_CAP = 8_000

    /** Room keeps at most this many sample rows per trip (stride the rest). */
    const val ROOM_SAMPLE_CAP = 24_000

    /** Refuel detector: level/speed/odo/rpm rows, not the whole journal. */
    const val REFUEL_SCAN_MAX_ROWS = 16_000

    /** OCR decode longest edge in px. Adobe Scan JPEGs were decoding at full sensor size. */
    const val OCR_MAX_EDGE = 1600

    /** Ride X-ray keeps a ring of recent shifts; running totals are the source of truth. */
    const val RIDE_SHIFT_CAP = 256

    fun strideFor(count: Int, cap: Int): Int =
        if (count <= cap) 1 else ((count + cap - 1) / cap).coerceAtLeast(2)
}
