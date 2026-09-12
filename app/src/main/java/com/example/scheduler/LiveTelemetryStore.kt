package com.example.scheduler

import com.example.model.LiveTelemetryValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for live OBD telemetry.
 *
 * ## Why this class exists (regression fix)
 *
 * The Škoda Kylaq answers the *same* generic OBD request from more than one ECU. The
 * reference trace `f39f1ebd` shows it plainly:
 *
 * ```
 * 19:33:16.009 RX < 7E804410C0F28     <- engine ECU  (7E8) : 970 rpm
 * 19:33:16.009 RX < 7E904410C0F34     <- gearbox/gateway ECU (7E9) : 973 rpm
 * ```
 *
 * An earlier change ("per-ECU telemetry isolation") started publishing **every** value
 * under a composite `"ECU_PID"` key (`"7E8_010C"`). That broke two things at once:
 *
 *  1. Every consumer of the display maps reads the plain PID key
 *     (`TelemetryDashboardContent`, `DrivingDashboardScreen`, `AiDoctorScreen`,
 *     `PidDetailScreen`, the Android Auto `ObdDashboardScreen`, the AI diagnostic
 *     context). `liveDecodedMap["010C"]` was permanently `null`, so the whole
 *     dashboard rendered "Not available" / "--" even while frames were flowing.
 *  2. `ObdScheduler.onTelemetrySignalUpdated()` read `telemetryValues["010C"]`,
 *     `["010D"]`, ... which were also always `null`, so driving state stayed
 *     `UNKNOWN` and fuel economy / gear estimation stayed "—".
 *
 * This store keeps **both** views and keeps them consistent:
 *
 *  * [telemetryMap] — ECU-aware, keyed `"ECU_PID"`, one entry per responding ECU so
 *    7E8 and 7E9 values never overwrite each other.
 *  * [decodedMap] / [numericMap] — plain PID keys for the UI and the powertrain
 *    engines, always sourced from a single deterministic *primary* ECU so the
 *    dashboard cannot jitter between two ECUs that report slightly different values.
 *
 * ## Primary ECU selection
 *
 * 1. the evidence-based preferred ECU for that PID (capability discovery),
 * 2. else the canonical engine ECU `7E8`,
 * 3. else the alphabetically first responding ECU (deterministic),
 * 4. sample quality outranks ECU identity: a fresh value beats a fresh failure marker,
 *    which beats a stale value. So if the preferred ECU stops answering, a still-responding
 *    secondary ECU takes over automatically, and one timeout never blanks the dashboard.
 *
 * The class is intentionally free of `android.*` dependencies so it can be unit
 * tested on a plain JVM.
 */
class LiveTelemetryStore {

    /** pidId -> (ecuId or [NO_ECU] -> value). Keeps every responding ECU separately. */
    private val byPid = ConcurrentHashMap<String, ConcurrentHashMap<String, LiveTelemetryValue>>()

    private val _telemetryMap = MutableStateFlow<Map<String, LiveTelemetryValue>>(emptyMap())
    /** ECU-aware telemetry, keyed `"ECU_PID"` (see class docs). */
    val telemetryMap: StateFlow<Map<String, LiveTelemetryValue>> = _telemetryMap.asStateFlow()

    private val _decodedMap = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Display strings keyed by plain PID id (e.g. `"010C"` -> `"976 RPM"`). */
    val decodedMap: StateFlow<Map<String, String>> = _decodedMap.asStateFlow()

    private val _numericMap = MutableStateFlow<Map<String, Double>>(emptyMap())
    /** Numeric values keyed by plain PID id, for charts and the powertrain engines. */
    val numericMap: StateFlow<Map<String, Double>> = _numericMap.asStateFlow()

    /** Display text used when a PID's last sample has gone stale. */
    companion object {
        const val NO_ECU = "DEFAULT"
        const val STALE_DISPLAY = "Not available (stale)"
        private const val ENGINE_ECU = "7E8"
    }

    /**
     * Records one decoded sample.
     *
     * @param pidId        plain PID id, e.g. `"010C"`
     * @param ecuId        responding ECU CAN id (e.g. `"7E8"`), or null when the frame
     *                     carried no header (ELM327 `ATH0`)
     * @param item         decoded telemetry value
     * @param preferredEcu evidence-based preferred ECU for this PID, if discovery knows one
     */
    fun publish(
        pidId: String,
        ecuId: String?,
        item: LiveTelemetryValue,
        preferredEcu: String? = null
    ) {
        val ecuKey = normalizeEcu(ecuId)
        byPid.getOrPut(pidId) { ConcurrentHashMap() }[ecuKey] = item
        republish(pidId, preferredEcu)
    }

    /**
     * Records an unavailable/failed sample (timeout, NO DATA, malformed, no frames).
     *
     * The plain-PID display entry is only overwritten when the failing ECU is the one
     * currently driving the display; a healthy secondary ECU keeps the dashboard alive.
     */
    fun publishUnavailable(
        pidId: String,
        ecuId: String?,
        item: LiveTelemetryValue,
        preferredEcu: String? = null
    ) {
        val ecuKey = normalizeEcu(ecuId)
        byPid.getOrPut(pidId) { ConcurrentHashMap() }[ecuKey] = item
        republish(pidId, preferredEcu)
    }

    /**
     * Flags samples older than [thresholdFor] as stale and refreshes the published views.
     *
     * @return true when at least one entry changed
     */
    fun markStale(
        nowMonotonic: Long,
        thresholdFor: (pidId: String) -> Long,
        preferredEcuFor: (pidId: String) -> String? = { null }
    ): Boolean {
        var changed = false
        byPid.forEach { (pidId, ecus) ->
            val threshold = thresholdFor(pidId)
            var pidChanged = false
            ecus.forEach { (ecuKey, item) ->
                if (!item.isStale && nowMonotonic - item.timestampMonotonic > threshold) {
                    ecus[ecuKey] = item.copy(isStale = true)
                    pidChanged = true
                }
            }
            if (pidChanged) {
                changed = true
                republish(pidId, preferredEcuFor(pidId))
            }
        }
        if (changed) _telemetryMap.value = flatten()
        return changed
    }

    /** Numeric value of the primary ECU for [pidId], or null when unavailable. */
    fun numericValue(pidId: String, preferredEcu: String? = null): Double? =
        primary(pidId, preferredEcu)?.takeIf { !it.isStale }?.numericValue

    /** Display string of the primary ECU for [pidId]. */
    fun displayValue(pidId: String, preferredEcu: String? = null): String? =
        _decodedMap.value[pidId] ?: primary(pidId, preferredEcu)?.displayValue

    /** The whole [LiveTelemetryValue] backing the plain-PID view, if any. */
    fun primary(pidId: String, preferredEcu: String? = null): LiveTelemetryValue? =
        selectPrimary(byPid[pidId]?.entries?.toList().orEmpty(), preferredEcu)?.value

    /** ECU ids currently holding a sample for [pidId] (sorted, deterministic). */
    fun ecusFor(pidId: String): List<String> =
        byPid[pidId]?.keys?.toList()?.sorted() ?: emptyList()

    /** Number of distinct PIDs that currently have a published display value. */
    val activePidCount: Int get() = _decodedMap.value.size

    fun clear() {
        byPid.clear()
        _telemetryMap.value = emptyMap()
        _decodedMap.value = emptyMap()
        _numericMap.value = emptyMap()
    }

    // ---------------------------------------------------------------- internals

    private fun normalizeEcu(ecuId: String?): String =
        ecuId?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: NO_ECU

    private fun ecuRank(ecuKey: String, preferredEcu: String?): Int = when {
        preferredEcu != null && ecuKey.equals(preferredEcu.trim().uppercase(), ignoreCase = true) -> 0
        ecuKey.equals(ENGINE_ECU, ignoreCase = true) -> 1
        ecuKey == NO_ECU -> 2
        else -> 3
    }

    /**
     * Sample quality tiers, best first:
     *
     *  * 0 — fresh, decodable value
     *  * 1 — fresh explicit failure (timeout / NO DATA / malformed)
     *  * 2 — stale sample that still holds a last known value
     *  * 3 — stale failure
     *
     * Tiering before ECU rank is what makes multi-ECU failover correct: a live timeout on
     * the preferred ECU (tier 1) loses to live data from a secondary ECU (tier 0), so the
     * dashboard keeps showing the value 7E9 is still reporting. It still beats a value
     * nobody has confirmed recently (tier 2), because "no answer right now" is the more
     * honest statement than a number that has not been refreshed.
     */
    private fun qualityTier(item: LiveTelemetryValue): Int = when {
        !item.isStale && item.isValid -> 0
        !item.isStale -> 1
        item.isValid -> 2
        else -> 3
    }

    /**
     * Deterministic primary-ECU selection: quality tier first, then preferred ECU, then the
     * canonical engine ECU, then the ECU id alphabetically (so the result never depends on
     * which of the two frames the adapter happened to deliver first).
     */
    private fun primaryComparator(
        preferredEcu: String?
    ): Comparator<Map.Entry<String, LiveTelemetryValue>> = compareBy(
        { qualityTier(it.value) },
        { ecuRank(it.key, preferredEcu) },
        { it.key }
    )

    private fun selectPrimary(
        entries: List<Map.Entry<String, LiveTelemetryValue>>,
        preferredEcu: String?
    ): Map.Entry<String, LiveTelemetryValue>? = entries.minWithOrNull(primaryComparator(preferredEcu))

    /**
     * Recomputes the plain-PID views from the ECU-aware store using [selectPrimary], so the
     * display never depends on which ECU answered last.
     */
    private fun republish(pidId: String, preferredEcu: String?) {
        val entries = byPid[pidId]?.entries?.toList()
        if (entries.isNullOrEmpty()) return

        val winnerValue = selectPrimary(entries, preferredEcu)?.value ?: return

        val display = when {
            winnerValue.isStale && winnerValue.isValid -> STALE_DISPLAY
            // Invalid samples carry a human readable placeholder ("Not available", raw hex
            // for research PIDs); joining the unit onto those produced "Not available RPM".
            !winnerValue.isValid -> winnerValue.displayValue
            else -> "${winnerValue.displayValue} ${winnerValue.unit}".trim()
        }

        val decoded = _decodedMap.value.toMutableMap()
        decoded[pidId] = display
        _decodedMap.value = decoded

        val numeric = _numericMap.value.toMutableMap()
        val numericValue = winnerValue.numericValue
        if (numericValue != null && !winnerValue.isStale && winnerValue.isValid) {
            numeric[pidId] = numericValue
        } else {
            numeric.remove(pidId)
        }
        _numericMap.value = numeric

        _telemetryMap.value = flatten()
    }

    private fun flatten(): Map<String, LiveTelemetryValue> {
        val out = LinkedHashMap<String, LiveTelemetryValue>()
        byPid.toSortedMap().forEach { (pidId, ecus) ->
            ecus.toSortedMap().forEach { (ecuKey, value) ->
                out["${ecuKey}_$pidId"] = value
            }
        }
        return out
    }
}
