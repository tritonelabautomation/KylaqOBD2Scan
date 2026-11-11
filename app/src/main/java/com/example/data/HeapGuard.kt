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

    /** Ride X-ray keeps a ring of recent shifts; running totals are the source of truth. */
    const val RIDE_SHIFT_CAP = 256
}
