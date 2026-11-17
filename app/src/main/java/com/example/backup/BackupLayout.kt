package com.example.backup

/**
 * What the files inside a backup/export ZIP mean (2026-09-16).
 *
 * Backup ZIPs are FLAT: `ZipExporter` writes `ZipEntry(file.name)`, so a Drive backup of
 * several trips contains `<id>.json`, `<id>_transactions.csv`, `<id>_samples.csv`,
 * `<id>_raw.txt` and `<id>_bundle.zip` for every session side by side. `ZipImporter` used
 * to pick the FIRST file of each type, which silently restored one trip out of many — and
 * could even mix session A's JSON with session B's CSV. Grouping by session id is what
 * makes "import all logs" actually mean all logs.
 *
 * Session ids may themselves contain underscores ("trip_import_test_123"), so ids are
 * recovered by stripping known suffixes, never by splitting on the first underscore.
 *
 * Pure JVM - unit-tested in BackupLayoutTest.
 */
object BackupLayout {

    /** Settings/ledger snapshot written by newer builds; absent in older backups. */
    const val SNAPSHOT_FILE_NAME = "app_data_snapshot.json"

    /** Raw log of a drive that was never finalized (see RawLogRecovery). */
    const val RAW_LOG_PREFIX = "raw_log_"

    enum class Kind {
        TRIP_JSON,
        TRANSACTIONS_CSV,
        SAMPLES_CSV,
        RAW_LOG_TXT,
        BUNDLE_ZIP,
        /** `raw_log_<id>.txt` - an unsaved drive, restored into files/raw_logs. */
        UNSAVED_RAW_LOG,
        DATA_SNAPSHOT,
        UNKNOWN
    }

    fun kindOf(fileName: String): Kind {
        val n = fileName.trim()
        if (n.equals(SNAPSHOT_FILE_NAME, ignoreCase = true)) return Kind.DATA_SNAPSHOT
        if (n.startsWith(RAW_LOG_PREFIX, ignoreCase = true) && n.endsWith(".txt", ignoreCase = true)) {
            return Kind.UNSAVED_RAW_LOG
        }
        return when {
            n.endsWith("_transactions.csv", ignoreCase = true) -> Kind.TRANSACTIONS_CSV
            n.endsWith("_samples.csv", ignoreCase = true) -> Kind.SAMPLES_CSV
            n.endsWith("_raw.txt", ignoreCase = true) -> Kind.RAW_LOG_TXT
            n.endsWith("_bundle.zip", ignoreCase = true) -> Kind.BUNDLE_ZIP
            n.endsWith(".json", ignoreCase = true) -> Kind.TRIP_JSON
            else -> Kind.UNKNOWN
        }
    }

    /** The trip session id a file belongs to, or null when it carries none. */
    fun sessionIdOf(fileName: String): String? {
        val n = fileName.trim()
        val id = when (kindOf(n)) {
            Kind.TRANSACTIONS_CSV -> stripSuffix(n, "_transactions.csv")
            Kind.SAMPLES_CSV -> stripSuffix(n, "_samples.csv")
            Kind.RAW_LOG_TXT -> stripSuffix(n, "_raw.txt")
            Kind.BUNDLE_ZIP -> stripSuffix(n, "_bundle.zip")
            Kind.TRIP_JSON -> stripSuffix(n, ".json")
            Kind.UNSAVED_RAW_LOG -> stripSuffix(stripPrefix(n, RAW_LOG_PREFIX), ".txt")
            Kind.DATA_SNAPSHOT, Kind.UNKNOWN -> null
        }
        return id?.takeUnless { it.isBlank() }
    }

    private fun stripSuffix(value: String, suffix: String): String =
        if (value.endsWith(suffix, ignoreCase = true)) value.dropLast(suffix.length) else value

    private fun stripPrefix(value: String, prefix: String): String =
        if (value.startsWith(prefix, ignoreCase = true)) value.drop(prefix.length) else value

    /**
     * Groups trip file names by session id. Names that carry no session id land in the
     * "" bucket, which callers treat as a legacy single-trip ZIP (a lone `log.json`,
     * `transactions.csv`, ... exported by older builds or by hand).
     */
    fun groupBySession(fileNames: List<String>): Map<String, List<String>> {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (name in fileNames) {
            val kind = kindOf(name)
            if (kind == Kind.DATA_SNAPSHOT || kind == Kind.UNSAVED_RAW_LOG || kind == Kind.UNKNOWN) continue
            val id = sessionIdOf(name)
            val key = if (id.isNullOrBlank()) "" else id
            grouped.getOrPut(key) { mutableListOf() }.add(name)
        }
        return grouped
    }

    /** True when a ZIP holds more than one trip session, so each must be imported separately. */
    fun isMultiSession(fileNames: List<String>): Boolean =
        groupBySession(fileNames).keys.count { it.isNotBlank() } > 1
}
