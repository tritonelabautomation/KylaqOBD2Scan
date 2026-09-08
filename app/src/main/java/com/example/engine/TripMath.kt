package com.example.engine

/**
 * Pure trip economics (VehIQ Trip Estimator + Trip Splitter, free and offline).
 */
object TripEstimator {

    data class Estimate(
        val distanceKm: Double,
        val fuelCost: Double,
        val tollCost: Double,
        val totalCost: Double,
        val driveHours: Double,
        val rideshareCost: Double,
        val savingVsRideshare: Double
    )

    /**
     * Estimates a door-to-door drive cost from the car's measured efficiency.
     * [tollPerKm] defaults to a rough Indian national-highway figure and
     * [ridesharePerKm] to a typical Uber/Ola city rate; both are overridable.
     */
    fun estimate(
        distanceKm: Double,
        kmPerL: Double,
        pricePerL: Double,
        avgSpeedKmh: Double = 45.0,
        tollPerKm: Double = 1.2,
        ridesharePerKm: Double = 14.0
    ): Estimate? {
        if (distanceKm <= 0 || kmPerL <= 0.1 || pricePerL <= 0 || avgSpeedKmh <= 1) return null
        val fuelCost = distanceKm / kmPerL * pricePerL
        val tollCost = distanceKm * tollPerKm
        val rideshareCost = distanceKm * ridesharePerKm
        val total = fuelCost + tollCost
        return Estimate(
            distanceKm = distanceKm,
            fuelCost = fuelCost,
            tollCost = tollCost,
            totalCost = total,
            driveHours = distanceKm / avgSpeedKmh,
            rideshareCost = rideshareCost,
            savingVsRideshare = rideshareCost - total
        )
    }
}

object TripSplitter {

    data class Transfer(val from: String, val to: String, val amount: Double)

    /**
     * Settles shared-drive expenses with the fewest transactions: everyone owes an equal share
     * of the total; payers hold credit. Greedy creditor/debtor matching is optimal for equal
     * shares (it produces at most n-1 transfers).
     */
    fun settle(members: List<String>, payments: List<Pair<String, Double>>): List<Transfer> {
        val known = members.distinct()
        if (known.size < 2) return emptyList()
        val total = payments.sumOf { it.second }
        if (total <= 0.0) return emptyList()
        val share = total / known.size
        val balance = known.associateWith { 0.0 }.toMutableMap()
        payments.forEach { (who, amount) ->
            if (who in balance) balance[who] = (balance[who] ?: 0.0) + amount - share
        }
        // Members who never paid still owe their share.
        val creditors = balance.map { it.key to it.value }
            .filter { it.second > 0.01 }
            .sortedByDescending { it.second }
            .toMutableList()
        val debtors = balance.map { it.key to it.value }
            .filter { it.second < -0.01 }
            .sortedBy { it.second }
            .toMutableList()

        val transfers = mutableListOf<Transfer>()
        var ci = 0
        var di = 0
        while (ci < creditors.size && di < debtors.size) {
            val (creditor, credit) = creditors[ci]
            val (debtor, debt) = debtors[di]
            val move = minOf(credit, -debt)
            transfers.add(Transfer(debtor, creditor, Math.round(move * 100.0) / 100.0))
            val newCredit = credit - move
            val newDebt = debt + move
            if (newCredit <= 0.01) ci++ else creditors[ci] = creditor to newCredit
            if (newDebt >= -0.01) di++ else debtors[di] = debtor to newDebt
        }
        return transfers
    }
}
