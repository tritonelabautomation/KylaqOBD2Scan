package com.example

import com.example.backup.AppDataSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole-app data snapshot (2026-09-16). Updating used to mean uninstalling, which wiped
 * everything outside `files/recordings/`: the fuel ledger, the ride / coast / tank insight
 * logs, expenses, documents, reminders, trip plans, maintenance and every setting. This is
 * the codec that carries them inside the same backup ZIP.
 */
class AppDataSnapshotTest {

    private val ledger = "1726372800000|39.85|45.0|12.7|full\n1726200000000|40.10|38.2|11.9|full"
    private val awkward = "quote \" and backslash \\ and newline \n and rupee ₹ and pipe |"

    private fun sampleStores(): Map<String, AppDataSnapshot.Store> = mapOf(
        "obd_research_prefs" to AppDataSnapshot.Store.fromRaw(
            mapOf(
                "ride_log" to ledger,
                "drive_coast_log" to awkward,
                "auto_record" to true,
                "last_backup_timestamp" to 1_726_372_800_000L,
                "poll_interval" to 150,
                "some_ratio" to 0.75f,
                "ignored_set" to setOf("a", "b"),   // unsupported type: skipped, not half-restored
                "ignored_null" to null
            )
        ),
        "fuel_log_prefs" to AppDataSnapshot.Store.fromRaw(mapOf("fuel_log" to ledger)),
        "empty_prefs" to AppDataSnapshot.Store.fromRaw(emptyMap<String, Any>())
    )

    @Test
    fun `raw preference values are dispatched by type`() {
        val store = AppDataSnapshot.Store.fromRaw(
            mapOf(
                "s" to "text",
                "b" to true,
                "l" to 42L,
                "i" to 7,
                "f" to 1.5f,
                "set" to setOf("x"),
                "n" to null
            )
        )
        assertEquals(mapOf("s" to "text"), store.strings)
        assertEquals(mapOf("b" to true), store.booleans)
        assertEquals(mapOf("l" to 42L), store.longs)
        assertEquals(mapOf("i" to 7), store.ints)
        assertEquals(mapOf("f" to 1.5f), store.floats)
        // Unsupported types are dropped rather than silently coerced into a wrong type.
        assertEquals(5, store.keyCount)
    }

    @Test
    fun `a snapshot round-trips every owner data store`() {
        val json = AppDataSnapshot.build("2026-09-16T04:05:06Z", sampleStores())
        val parsed = AppDataSnapshot.parse(json)

        assertTrue(parsed != null)
        assertEquals("2026-09-16T04:05:06Z", parsed!!.createdAt)
        assertEquals(
            "the empty store is dropped to keep the file small",
            setOf("obd_research_prefs", "fuel_log_prefs"),
            parsed.stores.keys
        )

        val main = parsed.stores["obd_research_prefs"]!!
        assertEquals(ledger, main.strings["ride_log"])
        assertEquals(awkward, main.strings["drive_coast_log"])
        assertEquals(true, main.booleans["auto_record"])
        assertEquals(1_726_372_800_000L, main.longs["last_backup_timestamp"])
        assertEquals(150, main.ints["poll_interval"])
        assertEquals(0.75f, main.floats["some_ratio"]!!, 0.0001f)

        assertEquals(ledger, parsed.stores["fuel_log_prefs"]!!.strings["fuel_log"])
    }

    @Test
    fun `ledger text survives newlines, quotes, backslashes and non-ascii`() {
        val json = AppDataSnapshot.build("now", sampleStores())
        val parsed = AppDataSnapshot.parse(json)!!
        assertEquals(2, parsed.stores["fuel_log_prefs"]!!.strings["fuel_log"]!!.split('\n').size)
        assertTrue(parsed.stores["obd_research_prefs"]!!.strings["drive_coast_log"]!!.contains("₹"))
        assertTrue(parsed.stores["obd_research_prefs"]!!.strings["drive_coast_log"]!!.contains("\\"))
    }

    @Test
    fun `unusable documents are rejected instead of half-restored`() {
        assertNull(AppDataSnapshot.parse(null))
        assertNull(AppDataSnapshot.parse(""))
        assertNull(AppDataSnapshot.parse("   "))
        assertNull(AppDataSnapshot.parse("not json"))
        assertNull(AppDataSnapshot.parse("{}"))
        assertNull(AppDataSnapshot.parse("""{"schema":0,"stores":{}}"""))
        assertNull(AppDataSnapshot.parse("""{"schema":1}"""))
    }

    @Test
    fun `a snapshot with no stores parses to an empty restore`() {
        val parsed = AppDataSnapshot.parse("""{"schema":1,"createdAt":"x","stores":{}}""")
        assertTrue(parsed != null)
        assertEquals(0, parsed!!.totalKeys)
        assertTrue(parsed.stores.isEmpty())
    }

    @Test
    fun `every owner data store is on the covered list`() {
        val stores = AppDataSnapshot.PREF_STORES
        assertTrue(stores.contains("fuel_log_prefs"))       // fuel ledger
        assertTrue(stores.contains("carpool_prefs"))        // car-pool ledger (owner 2026-09-20: nothing should be lost)
        assertTrue(stores.contains("obd_research_prefs"))   // settings + insight logs
        assertTrue(stores.contains("expense_prefs"))
        assertTrue(stores.contains("document_prefs"))
        assertTrue(stores.contains("reminder_prefs"))
        assertTrue(stores.contains("trip_plan_prefs"))
        assertTrue(stores.contains("maintenance_prefs"))
        assertEquals("no duplicates", stores.size, stores.distinct().size)
    }

    @Test
    fun `install-scoped stamps are not restored over a fresh install`() {
        assertFalse(AppDataSnapshot.isRestorable("obd_research_prefs", "last_update_check"))
        assertFalse(AppDataSnapshot.isRestorable("obd_research_prefs", "last_checkin_notified"))
        assertTrue(AppDataSnapshot.isRestorable("obd_research_prefs", "ride_log"))
        assertTrue(AppDataSnapshot.isRestorable("fuel_log_prefs", "fuel_log"))
        assertTrue(
            "the same key name in another store is that store's own data",
            AppDataSnapshot.isRestorable("fuel_log_prefs", "last_update_check")
        )
    }

    @Test
    fun `the snapshot file name matches the layout classifier`() {
        assertEquals("app_data_snapshot.json", AppDataSnapshot.FILE_NAME)
    }

    @Test
    fun theCarpoolLedgerSurvivesTheSnapshotRoundTrip() {
        // Owner 2026-09-20: "my car pool, all trips, & fuel logs everything should be backup to
        // Google drive ... nothing should be lost". The car-pool ledger lives in carpool_prefs;
        // before this test's store list gained it, a reinstall or phone move lost every ride.
        val log = "1758312780000|tripA|2026-09-20T01:33:00.000+05:30|30.0|Rohit\u001F192.0"
        val json = AppDataSnapshot.build(
            "2026-09-20T01:40:00.000+05:30",
            mapOf("carpool_prefs" to AppDataSnapshot.Store.fromRaw(mapOf("carpool_log" to log)))
        )
        val parsed = AppDataSnapshot.parse(json)!!
        assertEquals(log, parsed.stores["carpool_prefs"]!!.strings["carpool_log"])
        assertTrue(AppDataSnapshot.isRestorable("carpool_prefs", "carpool_log"))
    }
}
