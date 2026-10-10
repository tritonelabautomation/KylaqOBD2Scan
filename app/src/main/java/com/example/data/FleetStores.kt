package com.example.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Fleet-parity stores (VehIQ/FuelIO feature set, all free and offline):
 * general expenses, vehicle documents with expiry alerts, and custom reminders.
 * Codecs are dependency-free line formats so they are pure-JVM testable.
 */

// ---------------------------------------------------------------------------
// Expenses
// ---------------------------------------------------------------------------

object ExpenseCodec {
    const val FORMAT_VERSION = 1
    val CATEGORIES = listOf("Parking", "Tolls", "Car Wash", "Accessories", "Insurance", "Fine", "Other")

    data class ExpenseEntry(
        val idMs: Long,
        val dateUtc: String,
        val category: String,
        val amount: Double,
        val vendor: String,
        val note: String
    )

    fun encode(e: ExpenseEntry): String = listOf(
        "e$FORMAT_VERSION", e.idMs.toString(), e.dateUtc, esc(e.category),
        String.format(java.util.Locale.US, "%.2f", e.amount), esc(e.vendor), esc(e.note)
    ).joinToString("|")

    fun decode(line: String): ExpenseEntry? {
        val p = line.split('|')
        if (p.size != 7 || p[0] != "e$FORMAT_VERSION") return null
        return try {
            ExpenseEntry(p[1].toLong(), p[2], unesc(p[3]), p[4].toDouble(), unesc(p[5]), unesc(p[6]))
        } catch (e: NumberFormatException) {
            null
        }
    }

    internal fun esc(s: String) = s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")
    internal fun unesc(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) { 'p' -> append('|'); 'n' -> append('\n'); '\\' -> append('\\'); else -> append(n) }
                i += 2
            } else { append(c); i += 1 }
        }
    }
}

class ExpenseRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("expense_prefs", Context.MODE_PRIVATE)

    fun entries(): List<ExpenseCodec.ExpenseEntry> =
        prefs.getString("expense_log", null)?.split('\n')?.filter { it.isNotBlank() }
            ?.mapNotNull { ExpenseCodec.decode(it) } ?: emptyList()

    fun add(entry: ExpenseCodec.ExpenseEntry) {
        val lines = entries().map { ExpenseCodec.encode(it) }.toMutableList()
        lines.add(0, ExpenseCodec.encode(entry))
        while (lines.size > 500) lines.removeAt(lines.size - 1)
        prefs.edit().putString("expense_log", lines.joinToString("\n")).apply()
    }

    fun delete(idMs: Long) {
        prefs.edit().putString(
            "expense_log",
            entries().filterNot { it.idMs == idMs }.joinToString("\n") { ExpenseCodec.encode(it) }
        ).apply()
    }
}

// ---------------------------------------------------------------------------
// Documents
// ---------------------------------------------------------------------------

object DocumentCodec {
    const val FORMAT_VERSION = 1
    val TYPES = listOf("Insurance", "Registration (RC)", "Driving Licence", "PUC", "Warranty", "Other")

    data class VehicleDocument(
        val idMs: Long,
        val type: String,
        val title: String,
        val number: String,
        val issuer: String,
        val expiryUtc: String?,
        val expiryMs: Long?
    )

    fun encode(d: VehicleDocument): String = listOf(
        "d$FORMAT_VERSION", d.idMs.toString(), esc(d.type), esc(d.title), esc(d.number),
        esc(d.issuer), d.expiryUtc ?: "-", d.expiryMs?.toString() ?: "-"
    ).joinToString("|")

    fun decode(line: String): VehicleDocument? {
        val p = line.split('|')
        if (p.size != 8 || p[0] != "d$FORMAT_VERSION") return null
        return try {
            VehicleDocument(
                p[1].toLong(), unesc(p[2]), unesc(p[3]), unesc(p[4]), unesc(p[5]),
                p[6].takeIf { it != "-" }, p[7].takeIf { it != "-" }?.toLong()
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    internal fun esc(s: String) = ExpenseCodec.esc(s)
    internal fun unesc(s: String) = ExpenseCodec.unesc(s)
}

class DocumentRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("document_prefs", Context.MODE_PRIVATE)

    fun documents(): List<DocumentCodec.VehicleDocument> =
        prefs.getString("doc_log", null)?.split('\n')?.filter { it.isNotBlank() }
            ?.mapNotNull { DocumentCodec.decode(it) } ?: emptyList()

    fun add(doc: DocumentCodec.VehicleDocument) {
        val lines = documents().map { DocumentCodec.encode(it) }.toMutableList()
        lines.add(0, DocumentCodec.encode(doc))
        prefs.edit().putString("doc_log", lines.joinToString("\n")).apply()
    }

    fun delete(idMs: Long) {
        prefs.edit().putString(
            "doc_log",
            documents().filterNot { it.idMs == idMs }.joinToString("\n") { DocumentCodec.encode(it) }
        ).apply()
    }

    /** Documents expiring within [days] (or already expired), soonest first. */
    fun expiringWithin(days: Int, nowMs: Long = System.currentTimeMillis()): List<Pair<DocumentCodec.VehicleDocument, Long>> =
        documents()
            .mapNotNull { d -> d.expiryMs?.let { Triple(d, it, it - nowMs) } }
            .filter { it.third <= days * 24L * 60 * 60 * 1000 }
            .sortedBy { it.second }
            .map { it.first to it.third }
}

// ---------------------------------------------------------------------------
// Custom reminders
// ---------------------------------------------------------------------------

object ReminderCodec {
    const val FORMAT_VERSION = 1
    enum class Repeat { NONE, WEEKLY, MONTHLY, YEARLY }

    data class CustomReminder(
        val idMs: Long,
        val title: String,
        val dueMs: Long,
        val repeat: Repeat,
        val occurrencesLeft: Int?,
        val snoozedUntilMs: Long?,
        val completed: Boolean,
        val note: String
    )

    fun encode(r: CustomReminder): String = listOf(
        "r$FORMAT_VERSION", r.idMs.toString(), esc(r.title), r.dueMs.toString(), r.repeat.name,
        r.occurrencesLeft?.toString() ?: "-", r.snoozedUntilMs?.toString() ?: "-",
        if (r.completed) "1" else "0", esc(r.note)
    ).joinToString("|")

    fun decode(line: String): CustomReminder? {
        val p = line.split('|')
        if (p.size != 9 || p[0] != "r$FORMAT_VERSION") return null
        return try {
            CustomReminder(
                p[1].toLong(), unesc(p[2]), p[3].toLong(), Repeat.valueOf(p[4]),
                p[5].takeIf { it != "-" }?.toInt(), p[6].takeIf { it != "-" }?.toLong(),
                p[7] == "1", unesc(p[8])
            )
        } catch (e: NumberFormatException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Advances a repeating reminder to its next occurrence after [nowMs]. */
    fun nextDue(r: CustomReminder, nowMs: Long): Long {
        val day = 24L * 60 * 60 * 1000
        var due = r.dueMs
        when (r.repeat) {
            Repeat.NONE -> return due
            Repeat.WEEKLY -> while (due <= nowMs) due += 7 * day
            Repeat.MONTHLY -> while (due <= nowMs) due += 30 * day
            Repeat.YEARLY -> while (due <= nowMs) due += 365 * day
        }
        return due
    }

    internal fun esc(s: String) = ExpenseCodec.esc(s)
    internal fun unesc(s: String) = ExpenseCodec.unesc(s)
}

class ReminderRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("reminder_prefs", Context.MODE_PRIVATE)

    fun reminders(): List<ReminderCodec.CustomReminder> =
        prefs.getString("reminder_log", null)?.split('\n')?.filter { it.isNotBlank() }
            ?.mapNotNull { ReminderCodec.decode(it) } ?: emptyList()

    private fun saveAll(list: List<ReminderCodec.CustomReminder>) =
        prefs.edit().putString("reminder_log", list.joinToString("\n") { ReminderCodec.encode(it) }).apply()

    fun add(r: ReminderCodec.CustomReminder) = saveAll(reminders() + r)

    fun delete(idMs: Long) = saveAll(reminders().filterNot { it.idMs == idMs })

    fun snooze(idMs: Long, untilMs: Long) =
        saveAll(reminders().map { if (it.idMs == idMs) it.copy(snoozedUntilMs = untilMs) else it })

    /** Completes one occurrence; repeating reminders roll forward and count down. */
    fun complete(idMs: Long, nowMs: Long = System.currentTimeMillis()) =
        saveAll(reminders().map { r ->
            if (r.idMs != idMs) return@map r
            when {
                r.repeat == ReminderCodec.Repeat.NONE -> r.copy(completed = true)
                r.occurrencesLeft != null && r.occurrencesLeft <= 1 -> r.copy(completed = true)
                else -> r.copy(
                    dueMs = ReminderCodec.nextDue(r, nowMs),
                    occurrencesLeft = r.occurrencesLeft?.minus(1),
                    snoozedUntilMs = null
                )
            }
        })

    /** Active reminders that are due now (not snoozed, not completed). */
    fun due(nowMs: Long = System.currentTimeMillis()): List<ReminderCodec.CustomReminder> =
        reminders().filter { !it.completed && (it.snoozedUntilMs ?: 0) <= nowMs && it.dueMs <= nowMs }
}

// ---------------------------------------------------------------------------
// Voice parsing (Voice AI logging, free tier of everything)
// ---------------------------------------------------------------------------

/** Heuristic field extraction from a speech transcript: "filled 35 litres at BPCL, total 3200 rupees". */
object VoiceParse {
    private val NUMBER = Regex("(\\d+(?:[.,]\\d+)?)")

    fun liters(transcript: String): Double? =
        NUMBER.find(transcript.lowercase().replace(Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:litres|litre|l\\b)"), "LIT:$1 "))
            ?.let { null } ?: numberBefore(transcript, listOf("litre", "litres", " l ", "l."))

    fun price(transcript: String): Double? =
        numberBefore(transcript, listOf("rupee", "rupees", "rs", "rs.", "₹", "total", "paid"))

    fun amount(transcript: String): Double? = price(transcript) ?: numbers(transcript).firstOrNull()

    private fun numberBefore(text: String, keywords: List<String>): Double? {
        val lower = text.lowercase()
        val matches = NUMBER.findAll(lower).toList()
        for (kw in keywords) {
            val idx = lower.indexOf(kw)
            if (idx >= 0) {
                val candidate = matches.lastOrNull { it.range.first < idx }
                if (candidate != null) return candidate.value.replace(",", "").toDoubleOrNull()
            }
        }
        return null
    }

    fun numbers(text: String): List<Double> =
        NUMBER.findAll(text).map { it.value.replace(",", "").toDoubleOrNull() ?: 0.0 }.toList()
}
