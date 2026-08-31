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
                        decoderType = com.example.model.DecoderType.valueOf(obj.optString("decoderType", "RESEARCH_RAW")),
                        formulaDisplay = obj.optString("formulaDisplay", ""),
                        isResearch = obj.optBoolean("isResearch", false),
                        description = obj.optString("description", "")
                    )
                )
            }
            if (list.isEmpty()) DefaultPidDefinitions.getDefaults() else list
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
}
