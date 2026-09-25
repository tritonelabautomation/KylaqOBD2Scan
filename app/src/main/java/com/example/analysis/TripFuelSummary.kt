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
        val hasFuelSeries: Boolean = false,
        /**
         * Standstill seconds with the engine OFF (idle start-stop stalls), split out of
         * [idleSeconds] on 2026-09-15 so no coaching or overview ever charges an idle burn
         * for time the engine was not running. Appended last with defaults so every
         * existing positional construction keeps compiling.
         */
        val engineOffSeconds: Double = 0.0,
        /** Idle start-stop accounting: stalls, restart enrichment spikes, estimated fuel saved. */
        val startStop: StartStopAnalyzer.Summary = StartStopAnalyzer.Summary(),
        /**
         * Measured AC state from battery-voltage fluctuation (owner 2026-09-16): the first
         * OBSERVED compressor signal on this car - J1979 has no compressor PID, so before
         * this the app could only price AC from owner tags.
         */
        val ac: AcVoltageDetector.Result = AcVoltageDetector.Result(),
        /**
         * Battery voltage extremes of this trip WITH their instants (owner pipeline task 3,
         * 2026-09-16: "Voltage min max recording"), reduced from the stored 0142 samples so
         * even pre-migration trips report them. Null = the trip has no voltage samples.
         */
        val voltageExtremes: VoltageExtremes? = null,
        /**
         * Engine-load/rpm/fuel impact of the measured AC state (owner pipeline task 4,
         * 2026-09-16: "Engine load based on AC on off") - the compressor's actual cost on
         * this trip, or an incomparable result when a regime lacks engine-running samples.
         */
        val acLoad: AcLoadAnalyzer.Comparison = AcLoadAnalyzer.Comparison(),
        /**
         * What the battery sees during start-stop stalls (owner pipeline task 5,
         * 2026-09-16): mean/min voltage inside the measured stall windows vs the charging
         * baseline, and how many stalls overlapped measured AC-on time (blower demand).
         */
        val stopBattery: StopBatteryAnalyzer.Result = StopBatteryAnalyzer.Result(),
        /**
         * Engine torque from PID 0162 (owner pipeline task 6, 2026-09-16: "Engine torque
         * calculations"): mean and peak Nm over ENGINE-RUNNING samples, converted from the
         * ECU's percent-of-reference with its own 0164 reference torque when answered,
         * else the factory 178 Nm plateau ([torqueReferenceNm] says which was used).
         * Null when 0162 never answered on this trip - the card then shows nothing rather
         * than a fabricated torque (the fuel-energy recovery estimate lives in Insights,
         * labelled as an estimate, per the no-fake-values rule).
         */
        val meanTorqueNm: Double? = null,
        val peakTorqueNm: Double? = null,
        val torqueReferenceNm: Double? = null,
        /** Tank level % at trip start (PID 012F). */
        val startFuelPercent: Double? = null,
        /** Tank level % at trip end (PID 012F). */
        val endFuelPercent: Double? = null,
        /** Fuel level difference % (end - start). Negative = consumed, positive = refuel. */
        val fuelDeltaPercent: Double? = null,
        /** Approximate liters from % delta and standard tank capacity (50L). */
        val fuelDeltaLiters: Double? = null,
        /** True if level rose by >= 3.0% during the trip indicating a refuel / brim event. */
        val isRefuelBrimEvent: Boolean = false
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
        val rpmSeries = (byPid["010C"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to it } }
            .sortedBy { it.first }
        val voltageSeries = (byPid["0142"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to it } }
            .sortedBy { it.first }
        val fuelSeries = buildFuelSeries(byPid).sortedBy { it.first }
        val voltageExtremes = VoltageStats.extremes(voltageSeries)

        var fuelLiters = 0.0
        var coastSeconds = 0.0
        for (i in 1 until fuelSeries.size) {
            val (t0, rate) = fuelSeries[i - 1]
            val (t1, _) = fuelSeries[i]
            val dt = (t1 - t0).coerceIn(0L, MAX_GAP_MS) / 1000.0
            fuelLiters += rate * dt / 3600.0
            val speedAt = valueAt(speedSeries, t0)
            if (rate <= COAST_FUEL_LH && speedAt != null && speedAt > COAST_SPEED_KMH) {
                coastSeconds += dt
            }
        }

        // Distance: prefer odometer (PID 01A6) when it answered — it's the cluster's own
        // kilometres and survives any speed-integration glitch (owner 2026-09-21: recovered
        // 45 957-tx trip showed 0.1 km). Fall back to speed integration.
        val odoSeries = (byPid["01A6"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to it } }
            .sortedBy { it.first }
        var distanceKm = 0.0
        var odoDistanceUsed = false
        if (odoSeries.size >= 2) {
            val firstOdo = odoSeries.first().second
            val lastOdo = odoSeries.last().second
            val diff = lastOdo - firstOdo
            if (diff in 0.1..1000.0) {
                distanceKm = diff
                odoDistanceUsed = true
            }
        }

        var movingSeconds = 0.0
        var idleSeconds = 0.0
        var engineOffSeconds = 0.0
        var maxSpeed = 0.0
        var speedTimeIntegral = 0.0
        val histogram = LinkedHashMap<Int, Double>()
        for (i in 1 until speedSeries.size) {
            val (t0, v0) = speedSeries[i - 1].first to speedSeries[i - 1].second
            val t1 = speedSeries[i].first
            val dt = (t1 - t0).coerceIn(0L, MAX_GAP_MS) / 1000.0
            if (!odoDistanceUsed) {
                distanceKm += v0 * dt / 3600.0
            }
            speedTimeIntegral += v0 * dt
            if (v0 > maxSpeed) maxSpeed = v0
            if (v0 > 1.0) {
                movingSeconds += dt
                val bin = (v0.toInt() / 10) * 10
                histogram[bin] = (histogram[bin] ?: 0.0) + dt
            } else {
                val rpmAtT0 = valueAt(rpmSeries, t0)
                if (rpmAtT0 == null || rpmAtT0 > StartStopAnalyzer.ENGINE_RUNNING_RPM) {
                    idleSeconds += dt
                } else {
                    engineOffSeconds += dt
                }
            }
        }

        // Idle start-stop accounting over the same stored samples: stall detection,
        // restart enrichment spikes and the saved-fuel estimate (measured baseline first).
        val rpmByTs = rpmSeries.associate { it }
        val speedByTs = speedSeries.associate { it }
        val fuelByTs = fuelSeries.associate { it }
        val timeline = (rpmByTs.keys + speedByTs.keys + fuelByTs.keys).distinct().sorted()
        val startStop = StartStopAnalyzer.analyze(
            timeline.map { ts ->
                StartStopAnalyzer.Observation(
                    tsMs = ts,
                    rpm = rpmByTs[ts],
                    speedKmh = speedByTs[ts],
                    fuelLh = fuelByTs[ts]
                )
            }
        )

        // AC state from the voltage signature: engine-running samples only, self-calibrated
        // against this trip's own quietest windows (see AcVoltageDetector).
        val ac = AcVoltageDetector.detect(
            voltageSeries.map { (ts, v) ->
                AcVoltageDetector.Sample(tsMs = ts, voltageV = v, rpm = valueAt(rpmSeries, ts))
            }
        )

        // AC load impact (owner pipeline task 4): attribute ENGINE-RUNNING observations to
        // the measured AC-on / AC-off regimes. Running-only filter matters: a start-stop
        // stall inside an AC-on segment would average in load=0 and fake a lighter engine.
        val loadSeries = (byPid["0104"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to it } }
            .sortedBy { it.first }
        val acLoad = AcLoadAnalyzer.compare(
            segments = ac.segments,
            loadSeries = loadSeries.filter { (valueAt(rpmSeries, it.first) ?: 0.0) >= 400.0 },
            rpmSeries = rpmSeries.filter { it.second >= 400.0 },
            fuelSeries = fuelSeries.filter { (valueAt(rpmSeries, it.first) ?: 0.0) >= 400.0 }
        )

        // Engine torque (owner pipeline task 6): PID 0162 percent-of-reference converted
        // with the ECU's own 0164 reference when answered, else the factory plateau -
        // engine-running samples only, nothing fabricated when 0162 stays silent.
        // Reference torque source order matches the live path (ObdScheduler): 0163 first -
        // the owner's 2026-09-16 on-car validation proved THIS ECU reports its reference
        // torque (175 Nm) on 0163 and never answers 0164; then 0164 for other ECUs; then
        // the factory plateau.
        val torqueRef = (byPid["0163"] ?: emptyList()).mapNotNull { p -> p.value }.lastOrNull()
            ?: (byPid["0164"] ?: emptyList()).mapNotNull { p -> p.value }.lastOrNull()
            ?: com.example.engine.PowertrainModel.PEAK_TORQUE_NM
        val torqueNmSeries = (byPid["0162"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to it } }
            .sortedBy { it.first }
            .filter { (valueAt(rpmSeries, it.first) ?: 0.0) >= 400.0 }
            .map { (ts, pct) -> ts to com.example.engine.PowertrainModel.torqueNmFromPercent(pct, torqueRef) }
        val meanTorqueNm = if (torqueNmSeries.isNotEmpty()) torqueNmSeries.map { it.second }.average() else null
        val peakTorqueNm = torqueNmSeries.maxOfOrNull { it.second }

        // Battery picture during the stalls (owner pipeline task 5): slice the MEASURED
        // stall windows out of the stored voltage and compare with the charging baseline.
        val runningVoltage = voltageSeries.filter { (valueAt(rpmSeries, it.first) ?: 0.0) >= 400.0 }
        val stopBattery = StopBatteryAnalyzer.analyze(
            stopWindows = startStop.stopWindows,
            voltageSeries = voltageSeries,
            runningVoltageSeries = runningVoltage,
            acSegments = ac.segments
        )

        val firstTs = samples.minOf { it.timestampMs }
        val lastTs = samples.maxOf { it.timestampMs }
        val duration = (lastTs - firstTs).coerceAtLeast(0L) / 1000L
        val averageSpeed = if (duration > 0) distanceKm / duration * 3600.0 else 0.0
        val movingAverage = if (movingSeconds > 0) speedTimeIntegral / movingSeconds else 0.0
        val kmL = if (fuelLiters > 0.05 && distanceKm > 0.05) distanceKm / fuelLiters else null

        val levelSeries = (byPid["012F"] ?: byPid["2F"] ?: emptyList())
            .mapNotNull { p -> p.value?.let { p.timestampMs to it } }
            .sortedBy { it.first }
        val startFuelPercent = levelSeries.firstOrNull()?.second
        val endFuelPercent = levelSeries.lastOrNull()?.second
        val fuelDeltaPercent = if (startFuelPercent != null && endFuelPercent != null) {
            endFuelPercent - startFuelPercent
        } else null
        val fuelDeltaLiters = if (fuelDeltaPercent != null) {
            kotlin.math.abs(fuelDeltaPercent) / 100.0 * 50.0
        } else null
        val isRefuelBrimEvent = (fuelDeltaPercent ?: 0.0) >= 3.0

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
            hasFuelSeries = fuelSeries.isNotEmpty(),
            engineOffSeconds = engineOffSeconds,
            startStop = startStop,
            ac = ac,
            voltageExtremes = voltageExtremes,
            acLoad = acLoad,
            stopBattery = stopBattery,
            meanTorqueNm = meanTorqueNm,
            peakTorqueNm = peakTorqueNm,
            torqueReferenceNm = if (meanTorqueNm != null) torqueRef else null,
            startFuelPercent = startFuelPercent,
            endFuelPercent = endFuelPercent,
            fuelDeltaPercent = fuelDeltaPercent,
            fuelDeltaLiters = fuelDeltaLiters,
            isRefuelBrimEvent = isRefuelBrimEvent
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

    private fun valueAt(series: List<Pair<Long, Double>>, ts: Long): Double? {
        if (series.isEmpty()) return null
        // samples are ordered; nearest preceding point is good enough for a flag
        var candidate: Double? = null
        for (point in series) {
            if (point.first <= ts) candidate = point.second else break
        }
        return candidate
    }
}
