package com.example.ui.components

import java.util.Locale

/**
 * Non-Compose half of [FillMissingTripDialog].
 *
 * The dialog used to do all of its arithmetic, parsing and validation inline in composable
 * lambdas, which meant none of it could be unit tested on the JVM (the repo's `src/test`
 * suite deliberately runs without Robolectric in CI). Everything that decides *what gets
 * written* now lives here instead: the two log tabs, the invoice-cost parser, the PDF
 * attachment rules and the pollen-filter task tag. The composable only renders state and
 * forwards events, so the rules in this file are covered by `AllFeaturesTestSuite`.
 *
 * No Android types are imported on purpose - `androidx` belongs in the dialog, not here.
 */

/** The two things the dialog can write: a reconstructed drive, or a workshop inspection. */
enum class TripLogTab(val label: String) {
    TRIP("Trip"),
    SERVICE("Service Inspection")
}

/**
 * The cabin/pollen filter task.
 *
 * The Kylaq's cabin filter (`cabin_filter` in `MaintenanceCatalog`) is the one service item
 * an owner regularly does outside the dealer - buying the filter and fitting it in ten
 * minutes. Ticking it in the Service Inspection tab stamps a stable machine-readable tag into
 * the service notes so the entry stays identifiable as a pollen-filter job after it has been
 * round-tripped through the maintenance ledger, the fleet CSV and the PDF export.
 */
object PollenFilterTask {
    /** `MaintenanceCatalog.ServiceItem.id` of the cabin / pollen filter. */
    const val ITEM_ID = "cabin_filter"
    const val LABEL = "Pollen filter"
    const val TEST_TAG = "task_pollen_filter"
    /** Stable marker written into the service notes when the task is tagged. */
    const val NOTE_TAG = "[pollen-filter]"

    /** True when [itemId] is the pollen filter, i.e. the tag is meaningful for this task. */
    fun appliesTo(itemId: String?): Boolean = itemId == ITEM_ID

    fun isTagged(notes: String?): Boolean = notes?.contains(NOTE_TAG) == true

    /**
     * Adds the tag exactly once. Idempotent so re-saving an already tagged entry (or a note
     * the user typed the marker into by hand) cannot stack duplicates.
     */
    fun tagNote(notes: String?, tagged: Boolean = true): String {
        val base = notes?.trim().orEmpty()
        if (!tagged || isTagged(base)) return base
        return if (base.isEmpty()) NOTE_TAG else "$base $NOTE_TAG"
    }

    /** Removes the marker without disturbing anything else the technician wrote. */
    fun untagNote(notes: String?): String =
        notes?.replace(NOTE_TAG, "")?.replace(Regex("\\s{2,}"), " ")?.trim().orEmpty()
}

/**
 * One service task written from the Service Inspection tab.
 *
 * [invoiceCostRupees] is what the invoice actually charged - it is never derived from an
 * estimate, and stays null when the owner did not type it. [invoicePdfUri] keeps the picked
 * PDF's content URI (persisted read grant is taken by the caller) so the bill can be reopened
 * later; [invoicePdfName] is only the human-readable DISPLAY_NAME.
 */
data class ServiceInspectionEntry(
    val itemId: String,
    val dateMs: Long,
    val odometerKm: Double? = null,
    val invoiceCostRupees: Double? = null,
    val invoicePdfUri: String? = null,
    val invoicePdfName: String? = null,
    val notes: String = ""
) {
    val hasInvoice: Boolean get() = !invoicePdfUri.isNullOrBlank()

    /**
     * Notes as they are stored in the maintenance ledger. That ledger is a `|`-separated,
     * 140-character line format, so the invoice reference is compacted and the composed
     * string is capped here rather than being silently truncated mid-way by the encoder.
     */
    fun toServiceNotes(): String {
        val parts = mutableListOf<String>()
        val clean = notes.trim()
        if (clean.isNotEmpty()) parts.add(clean)
        if (hasInvoice) {
            val name = invoicePdfName?.takeIf { it.isNotBlank() } ?: "invoice.pdf"
            val cost = invoiceCostRupees?.let {
                " " + String.format(Locale.US, "\u20b9%.2f", it)
            }.orEmpty()
            parts.add("invoice: $name$cost")
        }
        return parts.joinToString(" \u00b7 ").take(ServiceInspectionRules.MAX_NOTE_CHARS)
    }
}

/**
 * Payload handed back to the caller when the Service Inspection tab is saved.
 *
 * Trips and service entries are different records (a trip is a drive, a service is a
 * workshop event), so saving an inspection never fabricates a drive and never touches the
 * odometer used for fuel maths unless [odometerKm] was genuinely supplied.
 */
data class ServiceInspectionRequest(
    val entries: List<ServiceInspectionEntry>,
    val note: String = ""
) {
    val odometerKm: Double? get() = entries.firstOrNull()?.odometerKm
    val totalInvoiceCostRupees: Double
        get() = entries.fold(0.0) { acc, entry -> acc + (entry.invoiceCostRupees ?: 0.0) }
    val hasInvoice: Boolean get() = entries.any { it.hasInvoice }
}

/**
 * Parsing and validation for the Service Inspection tab.
 *
 * Everything here is deliberately strict-and-honest in the same way as the analysis engines:
 * an unparseable amount is `null` (unknown), never `0.0` (free), because a wrong cost silently
 * corrupts every cost-per-km figure the owner reads afterwards.
 */
object ServiceInspectionRules {
    /** 2 000 000 km is well past the life of any Kylaq; beyond that it is a typo. */
    const val MAX_ODOMETER_KM = 2_000_000.0
    /** A single service bill in India is far below this; guards against a stuck key. */
    const val MAX_INVOICE_RUPEES = 10_000_000.0
    /** `MaintenanceCatalog` truncates the note field at 140 characters. */
    const val MAX_NOTE_CHARS = 140
    private const val MAX_NAME_CHARS = 80

    /** Only documents the app can actually reopen later. */
    const val PDF_MIME = "application/pdf"
    val PDF_MIME_TYPES = arrayOf(PDF_MIME)

    private val CURRENCY_NOISE = Regex("(?i)(\u20b9|inr|rs\\.?|,|\\s)")

    /**
     * `"₹1,250.50"`, `"Rs. 1250"`, `"INR 1 250"` and `"1250.5"` all parse to the same amount.
     * Blank, negative, non-finite or absurd values return null instead of a made-up number.
     */
    fun parseInvoiceCost(raw: String?): Double? {
        val cleaned = raw?.replace(CURRENCY_NOISE, "")?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value < 0.0 || value > MAX_INVOICE_RUPEES) return null
        return value
    }

    /** Accepts only `content://` / `file://` URIs - a bare path cannot be granted for later. */
    fun normalizePdfUri(raw: String?): String? {
        val uri = raw?.trim().orEmpty()
        if (uri.isEmpty()) return null
        if (!uri.startsWith("content://") && !uri.startsWith("file://")) return null
        return uri
    }

    /**
     * Human-readable bill name. Falls back to the URI's last path segment, decoded, because a
     * provider is allowed to answer the DISPLAY_NAME query with null.
     */
    fun pdfDisplayName(raw: String?, uri: String?): String? {
        val name = raw?.trim().orEmpty()
        if (name.isNotEmpty()) return name.take(MAX_NAME_CHARS)
        val segment = uri?.substringAfterLast('/', "")?.substringBefore('?').orEmpty()
        // A provider may hand back a segment with a literal '%' in it, which URLDecoder
        // rejects; the display name must never be able to crash the dialog.
        val decoded = try {
            java.net.URLDecoder.decode(segment, "UTF-8")
        } catch (e: IllegalArgumentException) {
            segment
        }
        return decoded.trim().takeIf { it.isNotEmpty() }?.take(MAX_NAME_CHARS)
    }

    /** Invoice line shown in the confirm sheet: `invoice.pdf · ₹1,250.00`. */
    fun invoiceSummary(name: String?, cost: Double?): String {
        val label = name?.takeIf { it.isNotBlank() } ?: "invoice.pdf"
        val amount = cost?.let { String.format(Locale.US, " \u00b7 \u20b9%.2f", it) }.orEmpty()
        return label + amount
    }

    fun validate(entry: ServiceInspectionEntry, nowMs: Long): InspectionValidation {
        val reasons = mutableListOf<String>()
        if (entry.itemId.isBlank()) reasons.add("Pick a service task")
        // One day of slack: a workshop bill dated tomorrow is a clock difference, not an error.
        if (entry.dateMs <= 0L || entry.dateMs > nowMs + DAY_MS) reasons.add("Service date is in the future")
        entry.odometerKm?.let {
            if (!it.isFinite() || it < 0.0 || it > MAX_ODOMETER_KM) reasons.add("Odometer is out of range")
        }
        entry.invoiceCostRupees?.let {
            if (!it.isFinite() || it < 0.0 || it > MAX_INVOICE_RUPEES) reasons.add("Invoice amount is out of range")
        }
        entry.invoicePdfUri?.let {
            if (normalizePdfUri(it) == null) reasons.add("Invoice must be a PDF document")
        }
        return InspectionValidation(reasons.isEmpty(), reasons)
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}

/** Validation outcome with the reasons spelled out, so the dialog can say what is wrong. */
data class InspectionValidation(val ok: Boolean, val reasons: List<String>) {
    val message: String get() = reasons.joinToString(" \u00b7 ")
}

/**
 * The dialog's complete state, transformed into the record it writes.
 *
 * Keeping this as a plain data class is what lets the tests assert the two hard rules:
 * the Trip tab always writes exactly one trip and the Service tab never does.
 */
data class FillTripDraft(
    val title: String = "",
    val startOdoKm: Double = 0.0,
    val endOdoKm: Double = 0.0,
    val startMs: Long = 0L,
    val durationSeconds: Long = 0L,
    val driveMode: String = "D",
    val acState: String = "ON",
    val fuelLitres: Double? = null,
    val carpoolRiders: String = "",
    val carpoolFareRupees: Double = 0.0,
    val notes: String = "",
    val serviceItemId: String? = null,
    val serviceDateMs: Long? = null,
    val invoiceCostRupees: Double? = null,
    val invoicePdfUri: String? = null,
    val invoicePdfName: String? = null,
    val pollenTaskTagged: Boolean = false
) {
    val distanceKm: Double get() = maxOf(0.0, endOdoKm - startOdoKm)
    val hasInvoice: Boolean get() = !invoicePdfUri.isNullOrBlank()
    /** The pollen tag is only offered for, and only ever applied to, the cabin filter. */
    val pollenTagAvailable: Boolean get() = PollenFilterTask.appliesTo(serviceItemId)

    /**
     * Trip note. The invoice reference rides along with the drive so the cost and the drive
     * stay connected in exports, without inventing a trip for a pure service event.
     */
    fun tripNote(): String {
        val parts = mutableListOf<String>()
        val clean = notes.trim()
        if (clean.isNotEmpty()) parts.add(clean)
        if (hasInvoice) {
            parts.add(
                "Service invoice: " + ServiceInspectionRules.invoiceSummary(invoicePdfName, invoiceCostRupees) +
                    " (" + invoicePdfUri + ")"
            )
        }
        return parts.joinToString(" \u00b7 ")
    }

    /** Tagged notes for a service entry; tag only sticks when it applies to the task. */
    fun serviceNote(): String =
        PollenFilterTask.tagNote(notes, pollenTaskTagged && pollenTagAvailable)

    /** Null when no task has been picked - the Service tab must not write a blank entry. */
    fun toServiceEntry(nowMs: Long = System.currentTimeMillis()): ServiceInspectionEntry? {
        val itemId = serviceItemId?.takeIf { it.isNotBlank() } ?: return null
        val dateMs = serviceDateMs ?: nowMs
        return ServiceInspectionEntry(
            itemId = itemId,
            dateMs = dateMs,
            odometerKm = endOdoKm.takeIf { it > 0.0 },
            invoiceCostRupees = invoiceCostRupees,
            invoicePdfUri = ServiceInspectionRules.normalizePdfUri(invoicePdfUri),
            invoicePdfName = ServiceInspectionRules.pdfDisplayName(invoicePdfName, invoicePdfUri),
            notes = serviceNote()
        )
    }

    fun toServiceRequest(nowMs: Long = System.currentTimeMillis()): ServiceInspectionRequest? {
        val entry = toServiceEntry(nowMs) ?: return null
        return ServiceInspectionRequest(listOf(entry), notes = notes.trim())
    }

    fun validateService(nowMs: Long = System.currentTimeMillis()): InspectionValidation {
        val entry = toServiceEntry(nowMs) ?: return InspectionValidation(false, listOf("Pick a service task"))
        val base = ServiceInspectionRules.validate(entry, nowMs)
        // [toServiceEntry] drops a URI it cannot reopen, so the raw value is checked here too:
        // silently discarding a bill the owner picked would look like a successful save.
        val rawUri = invoicePdfUri?.trim().orEmpty()
        if (rawUri.isNotEmpty() && ServiceInspectionRules.normalizePdfUri(rawUri) == null) {
            return InspectionValidation(
                false,
                base.reasons + "Invoice must be a PDF document (" + rawUri.take(40) + ")"
            )
        }
        return base
    }

    /**
     * Trip-tab payload. Only the Trip tab calls this, so a service inspection can never be
     * turned into a 0 km drive.
     */
    fun toManualTripRequest(): ManualTripRequest = ManualTripRequest(
        title = title.trim().ifBlank { "Missed Drive (Gap Filled)" },
        startOdometerKm = startOdoKm,
        endOdometerKm = endOdoKm,
        startTimestampMs = startMs,
        durationSeconds = maxOf(60L, durationSeconds),
        driveMode = driveMode,
        acState = acState,
        estimatedFuelLiters = fuelLitres,
        carpoolRiders = carpoolRiders.trim(),
        carpoolFare = carpoolFareRupees,
        notes = tripNote()
    )
}
