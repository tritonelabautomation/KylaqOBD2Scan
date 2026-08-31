package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth.ElmTransport
import com.example.model.DiagnosticProfile
import com.example.model.DiagnosticRequest
import com.example.model.DiagnosticResult
import com.example.model.ProfileDefinitions
import com.example.model.ProfileTestStatus
import com.example.model.ResponseStatus
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import com.example.protocol.PidDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import java.io.File
import java.io.FileWriter

class ProfilesViewModel(application: Application) : AndroidViewModel(application) {

    private val _profiles = MutableStateFlow(ProfileDefinitions.profiles)
    val profiles: StateFlow<List<DiagnosticProfile>> = _profiles.asStateFlow()

    private val _selectedProfile = MutableStateFlow<DiagnosticProfile?>(null)
    val selectedProfile: StateFlow<DiagnosticProfile?> = _selectedProfile.asStateFlow()

    private val _testResults = MutableStateFlow<List<DiagnosticResult>>(emptyList())
    val testResults: StateFlow<List<DiagnosticResult>> = _testResults.asStateFlow()

    private val _isRunningTest = MutableStateFlow(false)
    val isRunningTest: StateFlow<Boolean> = _isRunningTest.asStateFlow()

    private val _testProgress = MutableStateFlow(0f)
    val testProgress: StateFlow<Float> = _testProgress.asStateFlow()

    fun selectProfile(profileId: String) {
        _selectedProfile.value = _profiles.value.find { it.id == profileId }
        _testResults.value = emptyList()
        _testProgress.value = 0f
    }

    fun runProfileTest(transport: ElmTransport?) {
        val profile = _selectedProfile.value ?: return
        if (transport == null || !transport.isConnected) {
            return
        }

        viewModelScope.launch {
            _isRunningTest.value = true
            _testResults.value = emptyList()
            _testProgress.value = 0f

            val totalRequests = profile.requests.size
            val results = mutableListOf<DiagnosticResult>()

            // Temporarily pause polling if it's running via MainViewModel (this requires coordination)
            // But we assume MainViewModel polling is stopped before running tests.

            for ((index, request) in profile.requests.withIndex()) {
                if (!transport.isConnected) break

                val result = executeDiagnosticRequest(transport, request)
                results.add(result)
                _testResults.value = results.toList()

                _testProgress.value = (index + 1) / totalRequests.toFloat()
                
                // Small delay between requests to not overwhelm ECU
                withContext(Dispatchers.IO) { Thread.sleep(150) }
            }

            _isRunningTest.value = false
        }
    }

    private suspend fun executeDiagnosticRequest(transport: ElmTransport, request: DiagnosticRequest): DiagnosticResult {
        val command = request.requestCommand
        
        // Safety check
        val validation = SafetyValidator.validateCommand(command)
        if (validation is ValidationResult.Rejected) {
            return DiagnosticResult(
                request = request,
                rawTx = command,
                rawRx = "BLOCKED BY SAFETY VALIDATOR",
                parsedValue = "--",
                status = ProfileTestStatus.ERROR,
                responseTimeMs = 0
            )
        }

        val start = SystemClock.elapsedRealtime()
        val response = transport.sendCommand(command, 2000L)
        val end = SystemClock.elapsedRealtime()
        
        var profileStatus = ProfileTestStatus.PENDING
        var parsedValue = "--"
        var rawRx = response.rawText.ifBlank { response.lines.joinToString(" ") }

        if (response.status == ResponseStatus.TIMEOUT) {
            profileStatus = ProfileTestStatus.TIMEOUT
            rawRx = "NO DATA / TIMEOUT"
        } else if (response.status == ResponseStatus.NO_DATA) {
            profileStatus = ProfileTestStatus.UNSUPPORTED
            rawRx = "NO DATA"
        } else if (response.status == ResponseStatus.CAN_ERROR) {
            profileStatus = ProfileTestStatus.ERROR
        } else if (response.status == ResponseStatus.MALFORMED) {
             profileStatus = ProfileTestStatus.INVALID_RESPONSE
        } else if (response.lines.isNotEmpty()) {
             // Basic parsing
             // Find the payload
             val payloadHex = parsePayload(response.lines, request.service, request.identifier)
             if (payloadHex != null) {
                 profileStatus = if (request.isUds) ProfileTestStatus.EXPERIMENTAL else ProfileTestStatus.RESPONSE_RECEIVED
                 
                 // Try decode
                 try {
                     // Since we don't have PidDefinition here, we decode manually or use decoder if it matches
                     val value = decodeValue(payloadHex, request)
                     if (value != null) {
                         parsedValue = value
                         profileStatus = if (request.isUds || request.service == "22") ProfileTestStatus.EXPERIMENTAL else ProfileTestStatus.PARSED
                     }
                 } catch (e: Exception) {
                     profileStatus = ProfileTestStatus.RAW_RESPONSE_ONLY
                 }
             } else {
                 profileStatus = ProfileTestStatus.INVALID_RESPONSE
             }
        } else {
            profileStatus = ProfileTestStatus.UNKNOWN
        }

        return DiagnosticResult(
            request = request,
            rawTx = command,
            rawRx = rawRx,
            parsedValue = parsedValue,
            status = profileStatus,
            responseTimeMs = end - start
        )
    }

    private fun parsePayload(lines: List<String>, service: String, identifier: String): String? {
        val expectedRespSvc = String.format("%02X", service.toInt(16) + 0x40)
        for (line in lines) {
            val clean = line.replace(" ", "")
            if (clean.length >= 4 && clean.startsWith(expectedRespSvc)) {
                // If it's UDS 0x22, identifier is 2 bytes (4 chars)
                val expectedIdLen = identifier.length
                if (clean.length >= 4 + expectedIdLen) {
                    val idPart = clean.substring(2, 2 + expectedIdLen)
                    if (idPart.equals(identifier, ignoreCase = true)) {
                        return clean.substring(2 + expectedIdLen)
                    }
                }
            }
        }
        return null
    }

    private fun decodeValue(payloadHex: String, request: DiagnosticRequest): String? {
        // Simplified decoder for testing profiles
        if (payloadHex.length < 2) return null
        
        try {
            val a = if (payloadHex.length >= 2) payloadHex.substring(0, 2).toInt(16).toDouble() else 0.0
            val b = if (payloadHex.length >= 4) payloadHex.substring(2, 4).toInt(16).toDouble() else 0.0
            
            return when (request.decoderType.name) {
                "PERCENT_255" -> String.format("%.1f %%", a * 100 / 255)
                "TEMP_MINUS_40" -> String.format("%.0f °C", a - 40)
                "RPM_FORMULA" -> String.format("%.0f rpm", ((a * 256) + b) / 4)
                "RAW_A_KMH" -> String.format("%.0f km/h", a)
                "RAW_A_KPA" -> String.format("%.0f kPa", a)
                "FUEL_TRIM" -> String.format("%.1f %%", (a - 128) * 100 / 128)
                "TIMING_ADVANCE" -> String.format("%.1f °", a / 2 - 64)
                "VOLTAGE_1000" -> String.format("%.2f V", ((a * 256) + b) / 1000)
                "EQUIVALENCE_RATIO" -> String.format("%.3f λ", ((a * 256) + b) / 32768)
                else -> null
            }
        } catch (e: Exception) {
            return null
        }
    }

    fun exportResults(results: List<DiagnosticResult>): String {
        val dir = File(getApplication<Application>().filesDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        
        val file = File(dir, "profile_export_${System.currentTimeMillis()}.csv")
        FileWriter(file).use { writer ->
            writer.append("Timestamp,Profile,Service,Identifier,Name,RawTx,RawRx,ParsedValue,Status,ResponseTimeMs\n")
            for (res in results) {
                writer.append("${res.timestamp},")
                writer.append("${_selectedProfile.value?.name ?: "Unknown"},")
                writer.append("${res.request.service},")
                writer.append("${res.request.identifier},")
                writer.append("${res.request.name.replace(",", "")},")
                writer.append("${res.rawTx},")
                writer.append("${res.rawRx.replace(",", ";")},")
                writer.append("${res.parsedValue.replace(",", "")},")
                writer.append("${res.status.name},")
                writer.append("${res.responseTimeMs}\n")
            }
        }
        return file.absolutePath
    }
}
