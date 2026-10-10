package com.example.analysis

/**
 * Live threshold alerts (owner 2026-09-19, gap-analysis build 1): the keep-alive service watches
 * the live PID map while driving - screen off included - and raises a notification when a value
 * crosses a limit the owner sets. Pure on purpose: which alerts fire for one snapshot is a
 * function of (values, thresholds), so the vectors are unit-testable without Android.
 *
 * Silence rules: a PID the car is not answering right now is null in the map and produces
 * nothing - an absent sensor never alarms (honesty-first). Defaults are conservative for the
 * EA211: coolant 105 C, charging window 12.5-15.5 V with the engine running, fuel 12 %, 5500 rpm.
 */
object AlertRules {

    data class Thresholds(
        val coolantC: Double = 105.0,
        val voltageLowV: Double = 12.5,
        val voltageHighV: Double = 15.5,
        val fuelPct: Double = 12.0,
        val rpm: Double = 5500.0
    )

    data class Alert(val metric: String, val message: String)

    fun evaluate(v: Map<String, Double>, t: Thresholds): List<Alert> {
        val out = mutableListOf<Alert>()
        v["0105"]?.let { c ->
            if (c >= t.coolantC) out += Alert(
                "coolant",
                "Coolant %.0f C - at or above your %.0f C limit. Stop safely and check."
                    .format(java.util.Locale.US, c, t.coolantC)
            )
        }
        v["0142"]?.let { u ->
            if (u <= t.voltageLowV) out += Alert(
                "voltage-low",
                "System voltage %.1f V with the engine running - charging system suspect."
                    .format(java.util.Locale.US, u)
            )
            if (u >= t.voltageHighV) out += Alert(
                "voltage-high",
                "System voltage %.1f V - overcharge suspect.".format(java.util.Locale.US, u)
            )
        }
        v["012F"]?.let { f ->
            if (f <= t.fuelPct) out += Alert(
                "fuel",
                "Fuel level %.0f %% - at or below your %.0f %% limit. Plan a fill."
                    .format(java.util.Locale.US, f, t.fuelPct)
            )
        }
        v["010C"]?.let { r ->
            if (r >= t.rpm) out += Alert(
                "rpm",
                "Engine %.0f rpm - at or above your %.0f rpm limit."
                    .format(java.util.Locale.US, r, t.rpm)
            )
        }
        return out
    }
}
