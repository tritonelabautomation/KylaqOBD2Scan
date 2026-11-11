package com.example.analysis

import com.example.data.FuelLogCodec
import com.example.data.RefuelBunkRecord

/**
 * Attaches a pump fill to a trip.
 *
 * Time-from-start (+1 h) missed the 2026-11-11 Begumpet fill: the recovered run
 * started 15:59, the receipt is 17:09, and the previous Banjara Hills tank was
 * tagged instead. Cluster odometer is the memory the owner asked for — last
 * refill ODO 4,987 km sits inside 4,971 → 5,004, so that receipt belongs on
 * this trip, and its 40.27 L is the brim amount (not 71.8 % of the tank).
 */
object FillTripLinker {

    const val ODO_SLACK_KM = 8.0
    const val PRE_START_MS = 20L * 60_000L
    const val POST_END_MS = 45L * 60_000L
    const val DEFAULT_TRIP_MS = 6L * 3600_000L

    data class Link(
        val fill: FuelLogCodec.FuelEntry,
        val bunk: RefuelBunkRecord?,
        val happenedOnThisTrip: Boolean
    ) {
        val pumpLitres: Double get() = fill.liters
        val stationName: String
            get() = fill.station.ifBlank { bunk?.stationName.orEmpty() }
    }

    fun link(
        fills: List<FuelLogCodec.FuelEntry>,
        bunks: List<RefuelBunkRecord> = emptyList(),
        tripStartMs: Long,
        tripEndMs: Long? = null,
        tripStartOdoKm: Double? = null,
        tripEndOdoKm: Double? = null
    ): Link? {
        if (fills.isEmpty()) return null
        val bunkById = bunks.associateBy { it.idMs }
        fun wrap(f: FuelLogCodec.FuelEntry, onTrip: Boolean) =
            Link(f, bunkById[f.idMs] ?: bunks.firstOrNull { it.idMs == f.idMs }, onTrip)

        val odoLo = listOfNotNull(tripStartOdoKm, tripEndOdoKm).minOrNull()?.minus(ODO_SLACK_KM)
        val odoHi = listOfNotNull(tripStartOdoKm, tripEndOdoKm).maxOrNull()?.plus(ODO_SLACK_KM)
        if (odoLo != null && odoHi != null) {
            val onTrip = fills.filter { f ->
                val o = f.odometerKm ?: return@filter false
                o in odoLo..odoHi
            }
            if (onTrip.isNotEmpty()) {
                return wrap(onTrip.maxByOrNull { it.idMs }!!, true)
            }
            val start = tripStartOdoKm ?: odoLo
            val prior = fills.filter { f ->
                val o = f.odometerKm ?: return@filter false
                o <= start + ODO_SLACK_KM
            }
            if (prior.isNotEmpty()) {
                return wrap(prior.maxByOrNull { it.odometerKm ?: Double.NEGATIVE_INFINITY }!!, false)
            }
        }

        val endMs = tripEndMs ?: (tripStartMs + DEFAULT_TRIP_MS)
        val inWindow = fills.filter {
            it.idMs in (tripStartMs - PRE_START_MS)..(endMs + POST_END_MS)
        }
        if (inWindow.isNotEmpty()) {
            val pick = inWindow.maxByOrNull { it.idMs }!!
            return wrap(pick, pick.idMs >= tripStartMs - PRE_START_MS)
        }

        val before = fills.filter { it.idMs <= endMs + POST_END_MS }
        val pick = (if (before.isNotEmpty()) before else fills).maxByOrNull { it.idMs }!!
        return wrap(pick, false)
    }
}
