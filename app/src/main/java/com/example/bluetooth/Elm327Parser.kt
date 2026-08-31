package com.example.bluetooth

import com.example.model.ResponseStatus

/**
 * Parsed outcome of an ELM327 transaction
 */
data class ElmResponse(
    val rawText: String,
    val lines: List<String>,
    val status: ResponseStatus,
    val isPromptReceived: Boolean,
    val durationMs: Long,
    val errorMessage: String? = null
)

/**
 * Parses raw text from ELM327 adapter
 */
object Elm327Parser {

    fun parse(rawResponse: String, durationMs: Long = 0L): ElmResponse {
        val trimmed = rawResponse.trim()
        val hasPrompt = rawResponse.contains(">")

        // Clean out '>' prompt character and split by newline / carriage return
        val textWithoutPrompt = rawResponse.replace(">", "").trim()
        val lines = textWithoutPrompt.split(Regex("[\r\n]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) {
            return ElmResponse(
                rawText = rawResponse,
                lines = emptyList(),
                status = if (hasPrompt) ResponseStatus.OK else ResponseStatus.TIMEOUT,
                isPromptReceived = hasPrompt,
                durationMs = durationMs,
                errorMessage = if (!hasPrompt) "No response received within timeout" else null
            )
        }

        val upperAll = lines.joinToString(" ").uppercase()

        return when {
            upperAll.contains("NO DATA") -> {
                ElmResponse(
                    rawText = rawResponse,
                    lines = lines,
                    status = ResponseStatus.NO_DATA,
                    isPromptReceived = hasPrompt,
                    durationMs = durationMs,
                    errorMessage = "NO DATA"
                )
            }

            upperAll.contains("CAN ERROR") -> {
                ElmResponse(
                    rawText = rawResponse,
                    lines = lines,
                    status = ResponseStatus.CAN_ERROR,
                    isPromptReceived = hasPrompt,
                    durationMs = durationMs,
                    errorMessage = "CAN ERROR from ELM327"
                )
            }

            upperAll.contains("UNABLE TO CONNECT") -> {
                ElmResponse(
                    rawText = rawResponse,
                    lines = lines,
                    status = ResponseStatus.UNABLE_TO_CONNECT,
                    isPromptReceived = hasPrompt,
                    durationMs = durationMs,
                    errorMessage = "UNABLE TO CONNECT to vehicle CAN bus"
                )
            }

            upperAll.contains("BUS INIT: ERROR") || upperAll.contains("BUS INIT: ...ERROR") -> {
                ElmResponse(
                    rawText = rawResponse,
                    lines = lines,
                    status = ResponseStatus.BUS_INIT_ERROR,
                    isPromptReceived = hasPrompt,
                    durationMs = durationMs,
                    errorMessage = "Bus initialization error"
                )
            }

            upperAll.contains("?") -> {
                ElmResponse(
                    rawText = rawResponse,
                    lines = lines,
                    status = ResponseStatus.MALFORMED,
                    isPromptReceived = hasPrompt,
                    durationMs = durationMs,
                    errorMessage = "Unrecognized command (ELM327 returned '?')"
                )
            }

            upperAll.contains("BUFFER FULL") || upperAll.contains("FB ERROR") || upperAll.contains("DATA ERROR") -> {
                ElmResponse(
                    rawText = rawResponse,
                    lines = lines,
                    status = ResponseStatus.MALFORMED,
                    isPromptReceived = hasPrompt,
                    durationMs = durationMs,
                    errorMessage = "Adapter error: $upperAll"
                )
            }

            else -> {
                ElmResponse(
                    rawText = rawResponse,
                    lines = lines,
                    status = ResponseStatus.OK,
                    isPromptReceived = hasPrompt,
                    durationMs = durationMs,
                    errorMessage = null
                )
            }
        }
    }
}
