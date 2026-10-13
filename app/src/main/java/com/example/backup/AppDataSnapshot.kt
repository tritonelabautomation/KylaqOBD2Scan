package com.example.backup

import org.json.JSONObject

/**
 * Whole-app data snapshot, so a reinstall or a move to a new phone is lossless
 * (owner 2026-09-16: updating used to mean uninstalling, and "again import all logs").
 *
 * Recordings live in `files/recordings/` and were already covered by the Drive backup.
 * Everything else the owner can lose lives in SharedPreferences: the fuel ledger, ride /
 * coast / tank insight logs, expenses, documents, reminders, trip plans, maintenance and
 * every setting. Those stores are captured here as one JSON document that travels inside
 * the same backup ZIP as `app_data_snapshot.json`.
 *
 * Values are carried per type (string / boolean / long / int / float) because a ledger is
 * a String while a toggle is a Boolean, and restoring a Boolean as a String would leave
 * the setting silently unreadable. Permissions are NOT part of this: only Android can
 * grant those, so they are re-asked once after a reinstall.
 *
 * Pure JVM - unit-tested in AppDataSnapshotTest.
 */
object AppDataSnapshot {

    const val FILE_NAME = BackupLayout.SNAPSHOT_FILE_NAME
    const val SCHEMA = 1

    /** Every SharedPreferences file that holds owner data, newest stores included. */
    val PREF_STORES = listOf(
        "obd_research_prefs",   // settings + ride / coast / tank insight logs
        "fuel_log_prefs",       // fuel ledger (tank-to-tank entries)
        "carpool_prefs",        // car-pool ledger (rides, riders, amounts, trip links)
        "expense_prefs",        // expenses
        "document_prefs",       // documents
        "reminder_prefs",       // reminders
        "trip_plan_prefs",      // trip plans
        "maintenance_prefs"     // maintenance catalogue / services
    )

    /** One store's typed contents. */
    data class Store(
        val strings: Map<String, String> = emptyMap(),
        val booleans: Map<String, Boolean> = emptyMap(),
        val longs: Map<String, Long> = emptyMap(),
        val ints: Map<String, Int> = emptyMap(),
        val floats: Map<String, Float> = emptyMap()
    ) {
        val keyCount: Int
            get() = strings.size + booleans.size + longs.size + ints.size + floats.size

        companion object {
            /** Dispatches raw `SharedPreferences.getAll()` values into their typed buckets. */
            fun fromRaw(raw: Map<String, *>): Store {
                val s = LinkedHashMap<String, String>()
                val b = LinkedHashMap<String, Boolean>()
                val l = LinkedHashMap<String, Long>()
                val i = LinkedHashMap<String, Int>()
                val f = LinkedHashMap<String, Float>()
                for ((key, value) in raw) {
                    when (value) {
                        is String -> s[key] = value
                        is Boolean -> b[key] = value
                        is Long -> l[key] = value
                        is Int -> i[key] = value
                        is Float -> f[key] = value
                        // Anything else (StringSet, unknown) is skipped rather than
                        // half-restored; the logs and ledgers are all String-valued.
                        else -> Unit
                    }
                }
                return Store(s, b, l, i, f)
            }
        }
    }

    data class Snapshot(val createdAt: String, val stores: Map<String, Store>) {
        val totalKeys: Int get() = stores.values.sumOf { it.keyCount }
    }

    /** Serialises collected stores; empty stores are dropped to keep the file small. */
    fun build(createdAt: String, stores: Map<String, Store>): String {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("createdAt", createdAt)
        val storesObj = JSONObject()
        for ((name, store) in stores) {
            if (store.keyCount == 0) continue
            val one = JSONObject()
            one.put("string", JSONObject(store.strings as Map<*, *>))
            one.put("boolean", JSONObject(store.booleans as Map<*, *>))
            one.put("long", JSONObject(store.longs as Map<*, *>))
            one.put("int", JSONObject(store.ints as Map<*, *>))
            one.put("float", JSONObject(store.floats as Map<*, *>))
            storesObj.put(name, one)
        }
        root.put("stores", storesObj)
        return root.toString()
    }

    /** Parses a snapshot, returning null for absent or unreadable documents. */
    fun parse(json: String?): Snapshot? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JSONObject(json)
            if (root.optInt("schema", 0) < 1) return null
            val storesObj = root.optJSONObject("stores") ?: return null
            val stores = LinkedHashMap<String, Store>()
            for (name in storesObj.keys()) {
                val one = storesObj.optJSONObject(name) ?: continue
                stores[name] = Store(
                    strings = stringMap(one.optJSONObject("string")),
                    booleans = booleanMap(one.optJSONObject("boolean")),
                    longs = longMap(one.optJSONObject("long")),
                    ints = intMap(one.optJSONObject("int")),
                    floats = floatMap(one.optJSONObject("float"))
                )
            }
            Snapshot(root.optString("createdAt", ""), stores)
        } catch (_: Exception) {
            null
        }
    }

    private fun stringMap(o: JSONObject?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        o ?: return out
        for (k in o.keys()) out[k] = o.optString(k, "")
        return out
    }

    private fun booleanMap(o: JSONObject?): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        o ?: return out
        for (k in o.keys()) out[k] = o.optBoolean(k, false)
        return out
    }

    private fun longMap(o: JSONObject?): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        o ?: return out
        for (k in o.keys()) out[k] = o.optLong(k, 0L)
        return out
    }

    private fun intMap(o: JSONObject?): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        o ?: return out
        for (k in o.keys()) out[k] = o.optInt(k, 0)
        return out
    }

    private fun floatMap(o: JSONObject?): Map<String, Float> {
        val out = LinkedHashMap<String, Float>()
        o ?: return out
        for (k in o.keys()) out[k] = o.optDouble(k, 0.0).toFloat()
        return out
    }

    /** Keys that must never be restored: they describe the install, not the owner's data. */
    private val NEVER_RESTORE = setOf("last_update_check", "last_checkin_notified")

    /** True when a key is safe to write back into a fresh install. */
    fun isRestorable(storeName: String, key: String): Boolean {
        if (storeName == "obd_research_prefs" && key in NEVER_RESTORE) return false
        return true
    }
}
