package com.example.analysis

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Human-readable analysis report that travels inside shared trip ZIP bundles (owner
 * pipeline task 7, 2026-09-16: "Even with trends shared you didn't show me with &
 * without AC load analysis etc"). Before this, a shared bundle carried raw CSV/JSON but
 * none of the trip's measured analysis - the recipient had to re-derive everything.
 *
 * Every number comes from [TripFuelSummary] over the trip's OWN stored samples; sections
 * without measured evidence are OMITTED rather than guessed (no-fake-values rule). Pure
 * string building so it is unit-testable without Android.
 */
object TripAnalysisReport {

    fun build(tripName: String, summary: TripFuelSummary.Summary): String {
        val sb = StringBuilder()
        val us = Locale.US
        // Pinned to IST: this report is a record, and a record must not depend on the
        // zone the phone happens to be set to (owner mandate 2026-09-17).
        val timeFmt = com.example.data.RecordTime.formatter("HH:mm:ss")

        sb.appendLine("# Trip analysis - $tripName")
        sb.appendLine()
        sb.appendLine(
            "Generated on-device from this trip's stored OBD samples. " +
                "Sections without measured evidence are omitted - nothing here is modelled or assumed."
        )
        sb.appendLine()

        sb.appendLine("## Trip totals")
        sb.appendLine("- Distance: ${"%.2f".format(us, summary.distanceKm)} km")
        sb.appendLine("- Fuel used (integrated 015E/019D): ${"%.3f".format(us, summary.fuelLiters)} L")
        summary.kmPerLiter?.let { sb.appendLine("- Economy: ${"%.1f".format(us, it)} km/L") }
        summary.litersPer100Km?.let { sb.appendLine("- Consumption: ${"%.1f".format(us, it)} L/100 km") }
        sb.appendLine("- Duration: ${summary.durationSeconds / 60} min ${summary.durationSeconds % 60} s")
        sb.appendLine("- Average speed (all / moving): ${"%.1f".format(us, summary.averageSpeedKmh)} / ${"%.1f".format(us, summary.movingAverageSpeedKmh)} km/h")
        sb.appendLine("- Max speed: ${"%.1f".format(us, summary.maxSpeedKmh)} km/h")
        sb.appendLine("- Samples analysed: ${summary.sampleCount}")
        sb.appendLine()

        val ac = summary.ac
        if (ac.hasEvidence && ac.segments.isNotEmpty()) {
            sb.appendLine("## AC - MEASURED from battery-voltage ripple")
            sb.appendLine(
                "J1979 exposes no compressor PID on this ECU; the state below is detected from 0142 " +
                    "voltage fluctuation (clutch cycling + blower/fan pulsing), self-calibrated against " +
                    "this trip's own quietest windows."
            )
            sb.appendLine("- AC on: ${"%.0f".format(us, ac.acOnSeconds / 60.0)} min of ${summary.durationSeconds / 60} min trip")
            ac.switchEvents.firstOrNull()?.let { (ts, on) ->
                val flip = if (on) "ON" else "OFF"
                sb.appendLine("- First state flip: $flip at ${timeFmt.format(Date(ts))}")
            }
            ac.quietMadV?.let { sb.appendLine("- Quiet baseline: \u00b1${"%.3f".format(us, it)} V (median-absolute deviation)") }
            val weakNote = if (ac.confidence < 1.8) " (WEAK - treat as a hint)" else ""
            sb.appendLine("- Separation confidence: ${"%.1f".format(us, ac.confidence)}x$weakNote")
            sb.appendLine()

            sb.appendLine("### Engine load WITH vs WITHOUT AC (engine-running samples only)")
            val cmp = summary.acLoad
            if (cmp.isMeaningful) {
                cmp.loadDeltaPct?.let { sb.appendLine("- Mean engine load: ${"%.1f".format(us, cmp.acOn.meanLoadPct ?: 0.0)} % with AC vs ${"%.1f".format(us, cmp.acOff.meanLoadPct ?: 0.0)} % without -> ${"%+.1f".format(us, it)} pts") }
                cmp.rpmDelta?.let {
                    val onRpm = cmp.acOn.meanRpm?.let { v -> "%.0f".format(us, v) } ?: "-"
                    val offRpm = cmp.acOff.meanRpm?.let { v -> "%.0f".format(us, v) } ?: "-"
                    sb.appendLine("- Mean rpm: ${"%+.0f".format(us, it)} with AC ($onRpm vs $offRpm)")
                }
                cmp.fuelDeltaLh?.let {
                    val onFuel = cmp.acOn.meanFuelLh?.let { v -> "%.2f".format(us, v) } ?: "-"
                    val offFuel = cmp.acOff.meanFuelLh?.let { v -> "%.2f".format(us, v) } ?: "-"
                    sb.appendLine("- Mean fuel rate: ${"%+.2f".format(us, it)} L/h with AC ($onFuel vs $offFuel)")
                }
                sb.appendLine("- Regime coverage: ${"%.0f".format(us, cmp.acOn.seconds / 60.0)} min AC-on vs ${"%.0f".format(us, cmp.acOff.seconds / 60.0)} min AC-off (measured segments)")
            } else {
                sb.appendLine(
                    "- NOT COMPARABLE on this trip: one of the regimes lacks enough engine-running " +
                        "load samples (>= ${AcLoadAnalyzer.MIN_SAMPLES} needed each). No delta is fabricated."
                )
            }
            sb.appendLine()
        }

        summary.voltageExtremes?.let { v ->
            sb.appendLine("## Battery extremes (recorded)")
            sb.appendLine("- Min: ${"%.1f".format(us, v.minV)} V at ${timeFmt.format(Date(v.minTs))} (starter cranks look like this - check the timestamp)")
            sb.appendLine("- Max: ${"%.1f".format(us, v.maxV)} V at ${timeFmt.format(Date(v.maxTs))}")
            sb.appendLine()
        }

        val st = summary.startStop
        if (st.stopEvents > 0) {
            sb.appendLine("## Idle start-stop")
            sb.appendLine("- Stalls: ${st.stopEvents}, engine off ${"%.0f".format(us, st.engineOffSeconds)} s, restarts: ${st.restartCount}")
            val baselineNote = if (st.baselineSource == StartStopAnalyzer.Baseline.MEASURED) {
                "this trip's measured warm-idle rate"
            } else {
                "the 1.05 L/h model figure"
            }
            sb.appendLine("- Fuel saved vs idle baseline: ~${"%.2f".format(us, st.estimatedFuelSavedL)} L (ESTIMATE against $baselineNote)")
            val sbatt = summary.stopBattery
            if (sbatt.hasEvidence) {
                sb.appendLine("- Battery during ${sbatt.stopsWithVoltage} stall(s): mean ${"%.1f".format(us, sbatt.meanVInStops ?: 0.0)} V (min ${"%.1f".format(us, sbatt.minVInStops ?: 0.0)} V) vs ${"%.1f".format(us, sbatt.meanVRunning ?: 0.0)} V charging")
                sbatt.depressionV?.let { sb.appendLine("- Sag under stall loads: ${"%.1f".format(us, it)} V (proxy for 12 V draw - blower/fans run off the battery while stopped; the belt-driven compressor cannot)") }
                if (sbatt.acOnStops > 0) sb.appendLine("- ${sbatt.acOnStops}/${sbatt.acOnStopsTotal} stall(s) overlapped measured AC-on time: blower demand was demonstrably present")
            }
            sb.appendLine()
        }

        val tMean = summary.meanTorqueNm
        val tPeak = summary.peakTorqueNm
        if (tMean != null && tPeak != null) {
            sb.appendLine("## Engine torque (measured, PID 0162)")
            sb.appendLine("- Mean: ${"%.0f".format(us, tMean)} Nm • Peak: ${"%.0f".format(us, tPeak)} Nm (engine-running samples)")
            sb.appendLine("- Reference torque used: ${"%.0f".format(us, summary.torqueReferenceNm ?: 178.0)} Nm")
            sb.appendLine()
        }

        return sb.toString()
    }
}
