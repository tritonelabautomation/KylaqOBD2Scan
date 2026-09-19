package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.DefaultPidDefinitions
import com.example.model.PidDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class PollingSpeedMode(val displayName: String, val multiplier: Float, val description: String) {
    SAFE("Safe", 2.0f, "Prioritizes zero buffer overflow and high clone ELM327 stability (~500-1000ms)"),
    NORMAL("Normal", 1.0f, "Balanced logging rate for EA211 dynamic research (~200-400ms)"),
    FAST("Fast", 0.5f, "Maximum polling throughput on high-quality adapters (~100-150ms)")
}

class SettingsRepository(private val context: Context) {

    companion object {
        /**
         * Owner request 2026-09-12: "yes I want red theme on MID". The car's instrument
         * cluster firmware exposes no colour-theme channel on Kylaq (see
         * docs/reference/vag-coding-research.md), so the app's own telemetry face - the
         * MID substitute on your phone/HUD - defaults to RED SPORT out of the box.
         * Users who explicitly picked CYBER/AMBER keep their stored choice.
         */
        const val DEFAULT_ACCENT = "RED"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("obd_research_prefs", Context.MODE_PRIVATE)

    private val _pollingMode = MutableStateFlow(loadPollingMode())
    val pollingMode: StateFlow<PollingSpeedMode> = _pollingMode.asStateFlow()

    private val _pidDefinitions = MutableStateFlow(loadPidDefinitions())
    val pidDefinitions: StateFlow<List<PidDefinition>> = _pidDefinitions.asStateFlow()

    private val _initCommands = MutableStateFlow(loadInitCommands())
    val initCommands: StateFlow<List<String>> = _initCommands.asStateFlow()

    private val _vehicleName = MutableStateFlow(prefs.getString("vehicle_name", "Škoda Kylaq 1.0 TSI (EA211)") ?: "Škoda Kylaq 1.0 TSI (EA211)")
    val vehicleName: StateFlow<String> = _vehicleName.asStateFlow()

    private val _canHeader = MutableStateFlow(prefs.getString("can_header", "7DF") ?: "7DF")
    val canHeader: StateFlow<String> = _canHeader.asStateFlow()

    private val _sppUuid = MutableStateFlow(prefs.getString("spp_uuid", "00001101-0000-1000-8000-00805F9B34FB") ?: "00001101-0000-1000-8000-00805F9B34FB")
    val sppUuid: StateFlow<String> = _sppUuid.asStateFlow()

    // Cloud / Google Drive Backup Preferences
    private val _googleAccountEmail = MutableStateFlow(prefs.getString("google_account_email", null))
    val googleAccountEmail: StateFlow<String?> = _googleAccountEmail.asStateFlow()

    private val _autoCloudBackup = MutableStateFlow(prefs.getBoolean("auto_cloud_backup", false))
    val autoCloudBackup: StateFlow<Boolean> = _autoCloudBackup.asStateFlow()

    private val _lastBackupTimestamp = MutableStateFlow(prefs.getLong("last_backup_timestamp", 0L))
    val lastBackupTimestamp: StateFlow<Long> = _lastBackupTimestamp.asStateFlow()

    fun setGoogleAccountEmail(email: String?) {
        if (email != null) {
            prefs.edit().putString("google_account_email", email).apply()
        } else {
            prefs.edit().remove("google_account_email").apply()
        }
        _googleAccountEmail.value = email
    }

    fun setAutoCloudBackup(enabled: Boolean) {
        prefs.edit().putBoolean("auto_cloud_backup", enabled).apply()
        _autoCloudBackup.value = enabled
    }

    fun setLastBackupTimestamp(timestamp: Long) {
        prefs.edit().putLong("last_backup_timestamp", timestamp).apply()
        _lastBackupTimestamp.value = timestamp
    }

    /**
     * OAuth 2.0 **Web application** client ID used by Google Sign-In (Credential Manager).
     *
     * Stored on-device so sign-in can be configured without rebuilding the app: the value
     * committed in `res/values/strings.xml` is a placeholder, and Google Play services
     * rejects any request whose `serverClientId` is not registered for this exact package
     * name + signing SHA-1 (ApiException 10 / DEVELOPER_ERROR).
     */
    private val _googleWebClientId = MutableStateFlow(prefs.getString("google_web_client_id", null))
    val googleWebClientId: StateFlow<String?> = _googleWebClientId.asStateFlow()

    /** Display name of the signed-in Google account (null when signed out). */
    private val _googleAccountName = MutableStateFlow(prefs.getString("google_account_name", null))
    val googleAccountName: StateFlow<String?> = _googleAccountName.asStateFlow()

    fun setGoogleWebClientId(clientId: String?) {
        val clean = clientId?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) {
            prefs.edit().putString("google_web_client_id", clean).apply()
        } else {
            prefs.edit().remove("google_web_client_id").apply()
        }
        _googleWebClientId.value = clean
    }

    private val _defaultBtAddress = MutableStateFlow(prefs.getString("default_bt_address", null))
    val defaultBtAddress: StateFlow<String?> = _defaultBtAddress.asStateFlow()

    private val _autoConnect = MutableStateFlow(prefs.getBoolean("auto_connect_adapter", true))
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    private val _autoRecord = MutableStateFlow(prefs.getBoolean("auto_record_on_start", true))
    val autoRecord: StateFlow<Boolean> = _autoRecord.asStateFlow()

    /** The adapter auto-connect and both supervisors should use; null = pick by name. */
    fun setDefaultBtAddress(address: String?) {
        prefs.edit().putString("default_bt_address", address).apply()
        _defaultBtAddress.value = address
    }

    fun setAutoConnect(enabled: Boolean) {
        prefs.edit().putBoolean("auto_connect_adapter", enabled).apply()
        _autoConnect.value = enabled
    }

    fun setAutoRecord(enabled: Boolean) {
        prefs.edit().putBoolean("auto_record_on_start", enabled).apply()
        _autoRecord.value = enabled
    }

    // ── Car Welcome voice (owner 2026-09-16: the MacroDroid "Car Welcome" recipe, native) ──
    private val _welcomeEnabled = MutableStateFlow(prefs.getBoolean("welcome_enabled", true))
    val welcomeEnabled: StateFlow<Boolean> = _welcomeEnabled.asStateFlow()
    fun setWelcomeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("welcome_enabled", enabled).apply()
        _welcomeEnabled.value = enabled
    }

    private val _welcomeMessage = MutableStateFlow(
        prefs.getString("welcome_message", null) ?: WelcomeSpeaker.DEFAULT_MESSAGE
    )
    val welcomeMessage: StateFlow<String> = _welcomeMessage.asStateFlow()
    fun setWelcomeMessage(message: String) {
        prefs.edit().putString("welcome_message", message).apply()
        _welcomeMessage.value = message
    }

    private val _welcomeVolumeEnabled = MutableStateFlow(prefs.getBoolean("welcome_volume_enabled", true))
    val welcomeVolumeEnabled: StateFlow<Boolean> = _welcomeVolumeEnabled.asStateFlow()
    fun setWelcomeVolumeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("welcome_volume_enabled", enabled).apply()
        _welcomeVolumeEnabled.value = enabled
    }

    private val _welcomeVoiceId = MutableStateFlow(prefs.getString("welcome_voice_id", null))
    val welcomeVoiceId: StateFlow<String?> = _welcomeVoiceId.asStateFlow()
    fun setWelcomeVoiceId(id: String?) {
        prefs.edit().putString("welcome_voice_id", id).apply()
        _welcomeVoiceId.value = id
    }

    private val _welcomeVolumePct = MutableStateFlow(prefs.getInt("welcome_volume_pct", 70))
    val welcomeVolumePct: StateFlow<Int> = _welcomeVolumePct.asStateFlow()
    fun setWelcomeVolumePct(pct: Int) {
        prefs.edit().putInt("welcome_volume_pct", pct.coerceIn(0, 100)).apply()
        _welcomeVolumePct.value = pct.coerceIn(0, 100)
    }

    private val _midDisplayKmL = MutableStateFlow(
        if (prefs.contains("mid_display_km_l")) prefs.getFloat("mid_display_km_l", 0f).toDouble() else null
    )
    val midDisplayKmL: StateFlow<Double?> = _midDisplayKmL.asStateFlow()

    /** What the instrument cluster (MID/MFA) showed for the trip, for the About comparison. */
    fun setMidDisplayKmL(value: Double?) {
        val editor = prefs.edit()
        if (value == null) editor.remove("mid_display_km_l") else editor.putFloat("mid_display_km_l", value.toFloat())
        editor.apply()
        _midDisplayKmL.value = value
    }

    // ---- AC & climate behaviour prefs (additive 2026-09-09) ----
    private val _acSetTempC = MutableStateFlow(prefs.getFloat("ac_set_temp_c", 24f).toDouble())
    val acSetTempC: StateFlow<Double> = _acSetTempC.asStateFlow()

    fun setAcSetTempC(value: Double) {
        prefs.edit().putFloat("ac_set_temp_c", value.toFloat()).apply()
        _acSetTempC.value = value
    }

    private val _acAutoMode = MutableStateFlow(prefs.getBoolean("ac_auto_mode", false))
    val acAutoMode: StateFlow<Boolean> = _acAutoMode.asStateFlow()

    // ---- Fuelio-parity backup prefs (additive 2026-09-09) ----
    private val _backupShowNotification = MutableStateFlow(prefs.getBoolean("backup_show_notification", true))
    val backupShowNotification: StateFlow<Boolean> = _backupShowNotification.asStateFlow()

    fun setBackupShowNotification(enabled: Boolean) {
        prefs.edit().putBoolean("backup_show_notification", enabled).apply()
        _backupShowNotification.value = enabled
    }

    private val _backupWifiOnly = MutableStateFlow(prefs.getBoolean("backup_wifi_only", false))
    val backupWifiOnly: StateFlow<Boolean> = _backupWifiOnly.asStateFlow()

    fun setBackupWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean("backup_wifi_only", enabled).apply()
        _backupWifiOnly.value = enabled
    }

    private val _backupDaily = MutableStateFlow(prefs.getBoolean("backup_daily", false))
    val backupDaily: StateFlow<Boolean> = _backupDaily.asStateFlow()

    private val _accent = MutableStateFlow(prefs.getString("accent_theme", DEFAULT_ACCENT) ?: DEFAULT_ACCENT)
    val accent: StateFlow<String> = _accent.asStateFlow()

    fun setAccent(name: String) {
        prefs.edit().putString("accent_theme", name).apply()
        _accent.value = name
    }

    fun setBackupDaily(enabled: Boolean) {
        prefs.edit().putBoolean("backup_daily", enabled).apply()
        _backupDaily.value = enabled
    }

    fun setAcAutoMode(enabled: Boolean) {
        prefs.edit().putBoolean("ac_auto_mode", enabled).apply()
        _acAutoMode.value = enabled
    }

    fun setGoogleAccountName(name: String?) {
        if (!name.isNullOrBlank()) {
            prefs.edit().putString("google_account_name", name.trim()).apply()
            _googleAccountName.value = name.trim()
        } else {
            prefs.edit().remove("google_account_name").apply()
            _googleAccountName.value = null
        }
    }

    private fun loadPollingMode(): PollingSpeedMode {
        val name = prefs.getString("polling_mode", PollingSpeedMode.NORMAL.name)
        return try {
            PollingSpeedMode.valueOf(name ?: PollingSpeedMode.NORMAL.name)
        } catch (_: Exception) {
            PollingSpeedMode.NORMAL
        }
    }

    fun setPollingMode(mode: PollingSpeedMode) {
        prefs.edit().putString("polling_mode", mode.name).apply()
        _pollingMode.value = mode
    }

    fun setVehicleName(name: String) {
        prefs.edit().putString("vehicle_name", name).apply()
        _vehicleName.value = name
    }

    fun setCanHeader(header: String) {
        val clean = header.trim().uppercase()
        prefs.edit().putString("can_header", clean).apply()
        _canHeader.value = clean
    }

    fun setSppUuid(uuidStr: String) {
        prefs.edit().putString("spp_uuid", uuidStr.trim()).apply()
        _sppUuid.value = uuidStr.trim()
    }

    private fun loadInitCommands(): List<String> {
        val raw = prefs.getString("init_commands", null)
        return if (raw != null) {
            raw.split(";").filter { it.isNotBlank() }
        } else {
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP6")
        }
    }

    fun setInitCommands(commands: List<String>) {
        prefs.edit().putString("init_commands", commands.joinToString(";")).apply()
        _initCommands.value = commands
    }

    /**
     * Resolves a stored decoder type, falling back to the shipped default for that PID.
     *
     * FIX: `DecoderType.valueOf()` throws on any unknown/renamed value, and because the
     * whole load runs inside one try/catch a single corrupt entry used to discard every
     * user PID customisation.
     */
    private fun parseDecoderType(stored: String, pidId: String): com.example.model.DecoderType {
        if (stored.isNotBlank()) {
            try {
                return com.example.model.DecoderType.valueOf(stored)
            } catch (_: IllegalArgumentException) {
                // fall through to the shipped default
            }
        }
        return DefaultPidDefinitions.getDefaults().firstOrNull { it.id == pidId }?.decoderType
            ?: com.example.model.DecoderType.RESEARCH_RAW
    }

    /** Resolves a stored polling priority, falling back to the shipped default for that PID. */
    private fun parsePriority(stored: String, pidId: String): com.example.model.PollingPriority {
        if (stored.isNotBlank()) {
            try {
                return com.example.model.PollingPriority.valueOf(stored)
            } catch (_: IllegalArgumentException) {
                // fall through to the shipped default
            }
        }
        return DefaultPidDefinitions.getDefaults().firstOrNull { it.id == pidId }?.priority
            ?: com.example.model.PollingPriority.MEDIUM
    }

    private fun loadPidDefinitions(): List<PidDefinition> {
        val jsonStr = prefs.getString("pid_definitions_json", null)
        if (jsonStr == null) {
            return DefaultPidDefinitions.getDefaults()
        }

        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<PidDefinition>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    PidDefinition(
                        id = obj.getString("id"),
                        service = obj.getString("service"),
                        pid = obj.getString("pid"),
                        name = obj.getString("name"),
                        shortName = obj.getString("shortName"),
                        unit = obj.getString("unit"),
                        canHeader = obj.optString("canHeader", "7DF"),
                        expectedRxId = obj.optString("expectedRxId", "7E8"),
                        defaultIntervalMs = obj.optLong("defaultIntervalMs", 500L),
                        enabled = obj.optBoolean("enabled", true),
                        decoderType = parseDecoderType(obj.optString("decoderType", ""), obj.optString("id")),
                        formulaDisplay = obj.optString("formulaDisplay", ""),
                        isResearch = obj.optBoolean("isResearch", false),
                        description = obj.optString("description", ""),
                        // FIX: polling priority was never persisted, so every saved PID came
                        // back as MEDIUM and the scheduler lost its FAST-first ordering after
                        // the first settings write (RPM/speed then refreshed as slowly as the
                        // 5 s research PIDs).
                        priority = parsePriority(obj.optString("priority", ""), obj.optString("id"))
                    )
                )
            }
            if (list.isEmpty()) {
                DefaultPidDefinitions.getDefaults()
            } else {
                val refreshed = list.map { com.example.model.PidDefinitionReconciler.reconcile(it) }
                val defaultList = DefaultPidDefinitions.getDefaults()
                val existingKeys = refreshed.map { it.hexPid }.toSet()
                refreshed + defaultList.filter { !existingKeys.contains(it.hexPid) }
            }
        } catch (_: Exception) {
            DefaultPidDefinitions.getDefaults()
        }
    }

    fun savePidDefinitions(definitions: List<PidDefinition>) {
        _pidDefinitions.value = definitions
        try {
            val arr = JSONArray()
            for (pid in definitions) {
                val obj = JSONObject().apply {
                    put("id", pid.id)
                    put("service", pid.service)
                    put("pid", pid.pid)
                    put("name", pid.name)
                    put("shortName", pid.shortName)
                    put("unit", pid.unit)
                    put("canHeader", pid.canHeader)
                    put("expectedRxId", pid.expectedRxId)
                    put("defaultIntervalMs", pid.defaultIntervalMs)
                    put("enabled", pid.enabled)
                    put("decoderType", pid.decoderType.name)
                    put("formulaDisplay", pid.formulaDisplay)
                    put("isResearch", pid.isResearch)
                    put("description", pid.description)
                    put("priority", pid.priority.name)
                }
                arr.put(obj)
            }
            prefs.edit().putString("pid_definitions_json", arr.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun togglePidEnabled(pidId: String) {
        val updated = _pidDefinitions.value.map {
            if (it.id == pidId) it.copy(enabled = !it.enabled) else it
        }
        savePidDefinitions(updated)
    }

    fun resetPidDefaults() {
        val defaults = DefaultPidDefinitions.getDefaults()
        savePidDefinitions(defaults)
    }

    // ---------------------------------------------------------------------
    // Durable drive-insight logs. Coasting behaviour + mileage and closed
    // fuel-tank segments are appended when a recording ends (see
    // analysis/DriveInsightsStore.kt) so they survive app restarts.
    // ---------------------------------------------------------------------

    fun appendCoastLog(encoded: String) = appendInsightLog("drive_coast_log", encoded, 100)

    fun readCoastLog(): List<String> = readInsightLog("drive_coast_log")

    fun appendTankLog(encoded: String) = appendInsightLog("drive_tank_log", encoded, 60)

    fun appendRideLog(encoded: String) = appendInsightLog("ride_log", encoded, 80)

    fun readRideLog(): List<String> = readInsightLog("ride_log")

    fun readTankLog(): List<String> = readInsightLog("drive_tank_log")

    private fun appendInsightLog(key: String, encoded: String, maxEntries: Int) {
        val existing = prefs.getString(key, null)
        val lines = if (existing.isNullOrBlank()) mutableListOf() else existing.split('\n').toMutableList()
        lines.add(0, encoded)
        while (lines.size > maxEntries) lines.removeAt(lines.size - 1)
        prefs.edit().putString(key, lines.joinToString("\n")).apply()
    }

    private fun readInsightLog(key: String): List<String> =
        prefs.getString(key, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    private val _remindersEnabled = MutableStateFlow(prefs.getBoolean("reminders_enabled", true))
    val remindersEnabled: StateFlow<Boolean> = _remindersEnabled.asStateFlow()

    fun setRemindersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("reminders_enabled", enabled).apply()
        _remindersEnabled.value = enabled
    }

    private val _businessUsePct = MutableStateFlow(prefs.getInt("business_use_pct", 0))
    val businessUsePct: StateFlow<Int> = _businessUsePct.asStateFlow()

    /** Tax method "Actual Costs x business-use %": share of running costs claimed for work. */
    fun setBusinessUsePct(pct: Int) {
        prefs.edit().putInt("business_use_pct", pct.coerceIn(0, 100)).apply()
        _businessUsePct.value = pct.coerceIn(0, 100)
    }

    private val _weeklyCheckIn = MutableStateFlow(prefs.getBoolean("weekly_check_in", true))
    val weeklyCheckInEnabled: StateFlow<Boolean> = _weeklyCheckIn.asStateFlow()

    fun setWeeklyCheckIn(enabled: Boolean) {
        prefs.edit().putBoolean("weekly_check_in", enabled).apply()
        _weeklyCheckIn.value = enabled
    }

    fun lastCheckInNotifiedMs(): Long = prefs.getLong("last_checkin_notified", 0L)

    fun setLastCheckInNotifiedMs(ms: Long) = prefs.edit().putLong("last_checkin_notified", ms).apply()

    /**
     * When the owner last declined "Allow all the time", for [com.example.service.BackgroundLocationPolicy]'s
     * cooldown. Zero means never asked or never declined. Persisted because a decline has to survive
     * the process, or "stay quiet for a week" resets on every launch and becomes nagging.
     */
    fun lastBgLocationDeclinedMs(): Long = prefs.getLong("bg_location_last_declined", 0L)

    fun setLastBgLocationDeclinedMs(ms: Long) =
        prefs.edit().putLong("bg_location_last_declined", ms).apply()

    /**
     * Usable tank capacity in litres, for turning a 012F percentage rise into estimated litres at a
     * detected refuel. Boots at the Kylaq spec (50 L) and is re-derived from every pump-litre
     * calibration - the owner's 09-17 fill already implies 50.35 L from a 61.8 pt rise, so the
     * default is the spec and the calibration is the measurement.
     */
    fun tankCapacityL(): Double = prefs.getFloat("tank_capacity_l", 50.0f).toDouble()

    fun setTankCapacityL(litres: Double) =
        prefs.edit().putFloat("tank_capacity_l", litres.toFloat()).apply()

    /**
     * Last tank-level stamp of the previous session: "ts|levelPct|odoKmOrNull". The other half of a
     * between-sessions refuel event - the level rise itself happens while auto-record is off
     * (engine off at the pump), so only the two stamps either side of the gap can witness it.
     */
    fun lastLevelStamp(): Triple<Long, Double, Double?>? =
        prefs.getString("last_level_stamp", null)?.split("|")?.takeIf { it.size == 3 }?.let {
            val ts = it[0].toLongOrNull() ?: return@let null
            val lvl = it[1].toDoubleOrNull() ?: return@let null
            val odo = it[2].toDoubleOrNull()
            Triple(ts, lvl, odo)
        }

    fun setLastLevelStamp(tsMs: Long, levelPct: Double, odoKm: Double?) =
        prefs.edit().putString("last_level_stamp", "$tsMs|$levelPct|${odoKm ?: ""}").apply()

    /** Guard for the one-time refuel backfill pass over trips finalized before the detector. */
    fun refuelBackfillDone(): Boolean = prefs.getBoolean("refuel_backfill_done", false)

    fun setRefuelBackfillDone() = prefs.edit().putBoolean("refuel_backfill_done", true).apply()

    /**
     * Throttle stamp for the automatic in-app update check (2026-09-16). A manual
     * "Check for updates" tap is never throttled - only the silent launch check is.
     */
    fun lastUpdateCheckMs(): Long = prefs.getLong("last_update_check", 0L)

    fun setLastUpdateCheckMs(ms: Long) = prefs.edit().putLong("last_update_check", ms).apply()

    private val _appearanceMode = MutableStateFlow(prefs.getString("appearance_mode", "DARK") ?: "DARK")
    val appearanceMode: StateFlow<String> = _appearanceMode.asStateFlow()

    fun setAppearanceMode(mode: String) {
        prefs.edit().putString("appearance_mode", mode).apply()
        _appearanceMode.value = mode
    }

    private val _unitsMetric = MutableStateFlow(prefs.getBoolean("units_metric", true))
    val unitsMetric: StateFlow<Boolean> = _unitsMetric.asStateFlow()

    fun setUnitsMetric(metric: Boolean) {
        prefs.edit().putBoolean("units_metric", metric).apply()
        _unitsMetric.value = metric
    }

    private val _currencySymbol = MutableStateFlow(prefs.getString("currency_symbol", "\u20B9") ?: "\u20B9")
    val currencySymbol: StateFlow<String> = _currencySymbol.asStateFlow()

    fun setCurrencySymbol(symbol: String) {
        prefs.edit().putString("currency_symbol", symbol).apply()
        _currencySymbol.value = symbol
    }

    fun monthlyBudget(): Double? = prefs.getString("monthly_budget", null)?.toDoubleOrNull()

    fun setMonthlyBudget(amount: Double) =
        prefs.edit().putString("monthly_budget", String.format(java.util.Locale.US, "%.2f", amount)).apply()

    /** Persisted SAF tree URI of the chosen Google Drive backup folder (null = not linked). */
    fun driveTreeUri(): String? = prefs.getString("drive_tree_uri", null)

    fun setDriveTreeUri(uri: String?) {
        prefs.edit().apply {
            if (uri == null) remove("drive_tree_uri") else putString("drive_tree_uri", uri)
        }.apply()
    }
}
