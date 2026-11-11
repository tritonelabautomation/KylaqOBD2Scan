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

    /**
     * PIDs Factor 1 / tank / distance need. Mixed-stride of the whole journal
     * (Room 24k then UI 8k) stretched 015E past [TripFuelSummary]'s 15 s gap
     * cap, so 3.74 L became 1.15 L and 8.63 km/L became 17.68 km/L.
     * Last two hex digits: 5E fuel-rate, 9D mass-flow, 0D speed, A6 odo,
     * 2F tank %, 0C rpm.
     */
    private val FUEL_SUMMARY_TAILS = setOf("5E", "9D", "0D", "A6", "2F", "0C")

    const val FUEL_PID_CAP = 8_000

    fun keepForFuelSummary(pid: String): Boolean {
        val n = pid.trim().uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        if (n.length < 2) return false
        return n.takeLast(2) in FUEL_SUMMARY_TAILS
    }

    fun <T> capPerPid(items: List<T>, pidOf: (T) -> String, cap: Int = FUEL_PID_CAP): List<T> {
        if (items.isEmpty()) return items
        return items.groupBy(pidOf).flatMap { (_, list) ->
            val stride = strideFor(list.size, cap)
            if (stride == 1) list else list.filterIndexed { i, _ -> i % stride == 0 }
        }
    }
}
