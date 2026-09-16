package com.example.backup

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collects and applies an [AppDataSnapshot] against the app's SharedPreferences files.
 *
 * Deliberately thin: every serialisation, typing and filtering rule lives in the pure
 * [AppDataSnapshot] object so it is unit-testable without Robolectric.
 *
 * One honest caveat the UI must repeat: repositories cache their values in StateFlows that
 * were read at construction, so a restore only becomes fully visible after the app is
 * restarted. The import result therefore says "restart the app" rather than implying the
 * screens have already changed underneath.
 */
object PrefsSnapshotter {

    /** Current contents of every known owner-data store. */
    fun collect(context: Context): Map<String, AppDataSnapshot.Store> =
        AppDataSnapshot.PREF_STORES.associateWith { name ->
            AppDataSnapshot.Store.fromRaw(prefs(context, name).all)
        }

    /** Writes the snapshot to [dest] (the file name ZipExporter packs) and returns it. */
    fun writeToFile(context: Context, dest: File): File {
        val nowUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        dest.writeText(AppDataSnapshot.build(nowUtc, collect(context)))
        return dest
    }

    /** @return how many keys were written back. */
    fun apply(context: Context, snapshot: AppDataSnapshot.Snapshot): Int {
        var restored = 0
        for ((name, store) in snapshot.stores) {
            // Only known stores are restored: a ZIP from a future build must not sprinkle
            // arbitrary preference files into this install.
            if (name !in AppDataSnapshot.PREF_STORES) continue
            val editor: SharedPreferences.Editor = prefs(context, name).edit()
            for ((key, value) in store.strings) {
                if (!AppDataSnapshot.isRestorable(name, key)) continue
                editor.putString(key, value); restored++
            }
            for ((key, value) in store.booleans) {
                if (!AppDataSnapshot.isRestorable(name, key)) continue
                editor.putBoolean(key, value); restored++
            }
            for ((key, value) in store.longs) {
                if (!AppDataSnapshot.isRestorable(name, key)) continue
                editor.putLong(key, value); restored++
            }
            for ((key, value) in store.ints) {
                if (!AppDataSnapshot.isRestorable(name, key)) continue
                editor.putInt(key, value); restored++
            }
            for ((key, value) in store.floats) {
                if (!AppDataSnapshot.isRestorable(name, key)) continue
                editor.putFloat(key, value); restored++
            }
            editor.apply()
        }
        return restored
    }

    private fun prefs(context: Context, name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
}
