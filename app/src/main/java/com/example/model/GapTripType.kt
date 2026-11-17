package com.example.model

/**
 * Why an unlogged odometer gap is being filled.
 *
 * [SERVICE_INSPECTION] is the workshop visit path: pollen-filter task tagging, invoice
 * rupee cost, and an optional PDF invoice attachment ride along with the reconstructed trip.
 */
enum class GapTripType(val label: String, val defaultTitle: String) {
    MISSED_DRIVE("Missed drive", "Missed Drive (Gap Filled)"),
    SERVICE_INSPECTION("Service / inspection", "Service Inspection");

    companion object {
        /** Matches [com.example.data.MaintenanceCatalog] cabin / pollen filter item id. */
        const val POLLEN_FILTER_ITEM_ID = "cabin_filter"
    }
}

/**
 * Folds service-inspection extras into the trip note so they survive in the reconstructed
 * record without a schema bump. Pure, unit-tested.
 */
fun composeGapTripNotes(
    notes: String,
    type: GapTripType,
    pollenFilterServiced: Boolean = false,
    invoiceCost: Double? = null,
    invoicePdfUri: String? = null
): String {
    val extras = buildString {
        if (type == GapTripType.SERVICE_INSPECTION) {
            append("Service inspection")
            if (pollenFilterServiced) append("; pollen filter replaced")
            invoiceCost?.takeIf { it > 0.0 }?.let {
                append("; invoice ₹")
                append(String.format(java.util.Locale.US, "%.0f", it))
            }
            invoicePdfUri?.takeIf { it.isNotBlank() }?.let {
                append("; invoicePdf=")
                append(it)
            }
        }
    }
    val trimmed = notes.trim()
    return when {
        extras.isEmpty() -> trimmed
        trimmed.isEmpty() -> extras
        else -> "$trimmed | $extras"
    }
}
