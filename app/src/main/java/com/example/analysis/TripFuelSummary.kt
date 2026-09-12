package com.example.analysis

/**
 * Per-trip "log fuel" integration over stored telemetry samples.
 *
 * The trip table stores aggregates like max rpm but not fuel, so the honest numbers are
 * recomputed from the saved per-PID samples: fuel rate (015E L/h, or 019D g/s at 0.745 kg/L)
 * integrated over time, distance from speed (010D). Because the same 32 km route is driven
 * daily, comparing these summaries trip-by-trip *is* the route analysis: same distance,
 * different technique, different fuel.
 *
 * Also derived: average/moving speed, coasting seconds (fuel ≈ 0 while above 20 km/h - the
 * fuel-cut signature), idle seconds (engine running, not moving) and a 10 km/h speed histogram
 * for the "avg speed trend" view.
 */
object TripFuelSummary {

    data class SamplePoint(
        val pid: String,
        val timestampMs: Long,
        val value: Double?
    )

    data class Summary(
        val fuelLiters: Double,
        val distanceKm: Double,
        val kmPerLiter: Double?,
        val durationSeconds: Long,
        val averageSpeedKmh: Double,
        val movingAverageSpeedKmh: Double,
        val maxSpeedKmh: Double,
        val coastSeconds: Double,
        val idleSeconds: Double,
        val speedHistogram: List<Pair<Int, Double>>,
        val sampleCount: Int,
        /** Loud-failure flags (2026-09-12): the card must SAY "ECU never answered",
         *  not silently show 0.00 L / 0.0 km. */
        val hasSpeedSeries: Boolean = false,
        val hasFuelSeries: Boolean = false
    ) {
        val litersPer100Km: Double?
            get() = if (distanceKm > 0.05) fuelLiters / distanceKm * 100.0 else null
    }

    private const val FUEL_DENSITY_KG_L = 0.745
    private const val COAST_SPEED_KMH = 20.0
    private const val COAST_FUEL_LH = 0.15
    private const val MAX_GAP_MS = 15000L // slow PIDs legitimately arrive ~10 s apart

    fun summarize(samples: List<SamplePoint>): Summary {
        if (samples.isEmpty()) {
            return Summary(0.0, 0.0, null, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList(), 0, false, false)
        }

        // STORED-FORMAT FIX (2026-09-12, owner screenshot): TelemetrySampleEntity.pid is
        // written from TransactionRecord.pid, which carries the 2-hex PID suffix ("0D",
        // "5E", "9D"). The 4-hex lookups below ("010D", "015E", "019D") therefore matched
        // NOTHING and every sample-derived stat silently read zero while the trip-level
        // aggregates (2-hex matching) looked fine. Normalise both spellings to 4-hex.
        val byPid = samples.groupBy { normalizePidKey(it.pid) }
        val speedSeries = (byPid["010D"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to it } }
            .sortedBy { it.first }
        val fuelSeries = buildFuelSeries(byPid).sortedBy { it.first }

        var fuelLiters = 0.0
        var coastSeconds = 0.0
        for (i in 1 until fuelSeries.size) {
            val (t0, rate) = fuelSeries[i - 1]
            val (t1, _) = fuelSeries[i]
            val dt = (t1 - t0).coerceIn(0L, MAX_GAP_MS) / 1000.0
            fuelLiters += rate * dt / 3600.0
            val speedAt = speedAt(speedSeries, t0)
            if (rate <= COAST_FUEL_LH && speedAt != null && speedAt > COAST_SPEED_KMH) {
                coastSeconds += dt
            }
        }

        var distanceKm = 0.0
        var movingSeconds = 0.0
        var idleSeconds = 0.0
        var maxSpeed = 0.0
        var speedTimeIntegral = 0.0
        val histogram = LinkedHashMap<Int, Double>()
        for (i in 1 until speedSeries.size) {
            val (t0, v0) = speedSeries[i - 1].first to speedSeries[i - 1].second
            val t1 = speedSeries[i].first
            val dt = (t1 - t0).coerceIn(0L, MAX_GAP_MS) / 1000.0
            distanceKm += v0 * dt / 3600.0
            speedTimeIntegral += v0 * dt
            if (v0 > maxSpeed) maxSpeed = v0
            if (v0 > 1.0) {
                movingSeconds += dt
                val bin = (v0.toInt() / 10) * 10
                histogram[bin] = (histogram[bin] ?: 0.0) + dt
            } else {
                idleSeconds += dt
            }
        }

        val firstTs = samples.minOf { it.timestampMs }
        val lastTs = samples.maxOf { it.timestampMs }
        val duration = (lastTs - firstTs).coerceAtLeast(0L) / 1000L
        val averageSpeed = if (duration > 0) distanceKm / duration * 3600.0 else 0.0
        val movingAverage = if (movingSeconds > 0) speedTimeIntegral / movingSeconds else 0.0
        val kmL = if (fuelLiters > 0.05 && distanceKm > 0.05) distanceKm / fuelLiters else null

        return Summary(
            fuelLiters = fuelLiters,
            distanceKm = distanceKm,
            kmPerLiter = kmL,
            durationSeconds = duration,
            averageSpeedKmh = averageSpeed,
            movingAverageSpeedKmh = movingAverage,
            maxSpeedKmh = maxSpeed,
            coastSeconds = coastSeconds,
            idleSeconds = idleSeconds,
            speedHistogram = histogram.entries.map { it.key to it.value }.sortedBy { it.first },
            sampleCount = samples.size,
            hasSpeedSeries = speedSeries.isNotEmpty(),
            hasFuelSeries = fuelSeries.isNotEmpty()
        )
    }

    /** Accepts "0D"/"010D"/"10D" and friends; canonicalises to the 4-hex service-01 form. */
    fun normalizePidKey(pid: String): String {
        val clean = pid.trim().uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        return when (clean.length) {
            2 -> "01$clean"
            3 -> "0$clean"
            else -> clean
        }
    }

    /** Fuel rate in L/h from 015E directly, or 019D mass flow converted at petrol density. */
    private fun buildFuelSeries(byPid: Map<String, List<SamplePoint>>): List<Pair<Long, Double>> {
        val volume = byPid["015E"] ?: emptyList()
        if (volume.any { it.value != null }) {
            return volume.mapNotNull { p -> p.value?.let { p.timestampMs to it } }
        }
        val mass = byPid["019D"] ?: emptyList()
        return mass.mapNotNull { p -> p.value?.let { p.timestampMs to it * 3600.0 / (FUEL_DENSITY_KG_L * 1000.0) } }
    }

    private fun speedAt(series: List<Pair<Long, Double>>, ts: Long): Double? {
        if (series.isEmpty()) return null
        // samples are ordered; nearest preceding point is good enough for a flag
        var candidate: Double? = null
        for (point in series) {
            if (point.first <= ts) candidate = point.second else break
        }
        return candidate
    }
}
