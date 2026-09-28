import io, sys

def patch(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        assert '"""' not in old and '"""' not in new
        if old == new:
            print('NOOP :: ' + repr(old[:60])); sys.exit(1)
        n = s.count(old)
        if n != 1:
            print('FAIL count=' + str(n) + ' :: ' + repr(old[:70])); sys.exit(1)
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8').write(s)
    print('OK ' + path + ' (' + str(len(pairs)) + ' anchors)')

S = 'app/src/main/java/com/example/discovery/PidDiscoveryService.kt'

OLD_A = """    val decodedValue: String? = null,
    val respondingCanId: String? = null
)"""

NEW_A = """    val decodedValue: String? = null,
    val respondingCanId: String? = null,
    /**
     * Data quality of [decodedValue], reported separately from [directStatus] since
     * 2026-09-17. directStatus answers whether the ECU replied positively, NOT whether
     * the value is usable: the owner export of the 2026-09-16 run carried six rows that
     * read DIRECT_VALIDATED while their decoded value was an implausible-raw sentinel or
     * Not available. Two facts, two fields - never one dressed as the other.
     */
    val dataQuality: String = "NOT_DECODED"
)"""

OLD_B = """                    val decodedVal = if (isPositive && payloadBytes.isNotEmpty()) {
                        try {
                            com.example.protocol.PidDecoder.decode(def, payloadBytes).displayValue
                        } catch (_: Exception) { null }
                    } else null

                    appendLog("PID $cleanPid Validation: status=$status (${latencyMs}ms) | Decoded: $decodedVal | Responding ECU: $respondingCanId")"""

NEW_B = """                    val decodedVal = if (isPositive && payloadBytes.isNotEmpty()) {
                        try {
                            com.example.protocol.PidDecoder.decode(def, payloadBytes).displayValue
                        } catch (_: Exception) { null }
                    } else null

                    // A POSITIVE REPLY IS NOT A USABLE VALUE. Classify what came back so the
                    // report can never again claim a PID is validated while its payload is a
                    // sentinel the ECU returns for "not implemented / not available now".
                    val dataQuality = when {
                        !isPositive -> "NO_REPLY"
                        decodedVal == null -> "NOT_DECODED"
                        decodedVal.startsWith("implausible") -> "RESPONDED_IMPLAUSIBLE"
                        decodedVal == "NO DATA" -> "RESPONDED_NO_DATA"
                        decodedVal == "INVALID_RESPONSE" -> "RESPONDED_MISMATCHED_FRAME"
                        decodedVal == "Not available" -> "RESPONDED_NOT_AVAILABLE"
                        else -> "PLAUSIBLE"
                    }

                    appendLog("PID $cleanPid Validation: status=$status (${latencyMs}ms) | Decoded: $decodedVal | Data: $dataQuality | Responding ECU: $respondingCanId")"""

OLD_C = """                        decodedValue = decodedVal,
                        respondingCanId = respondingCanId
                    )"""

NEW_C = """                        decodedValue = decodedVal,
                        respondingCanId = respondingCanId,
                        dataQuality = dataQuality
                    )"""

OLD_D = """        json.put("ranges", rangesArr)

        // Supported PIDs"""

NEW_D = """        json.put("ranges", rangesArr)

        // Range markers (0x20/0x40/0x60/0x80/0xA0/0xC0) are availability bitmaps, NOT
        // parameters. They are listed explicitly since 2026-09-17 so a reader can never
        // mistake one for a data channel again: the owner export of 2026-09-16 listed PID
        // 60 and PID 80 under supportedPids and then "validated" PID 80 as a diesel
        // particulate filter temperature on a petrol car.
        val markersArr = JSONArray()
        for (range in _discoveredRanges.value) {
            if (range.basePid < 0xE0) markersArr.put("01%02X".format(range.basePid + 0x20))
        }
        json.put("rangeMarkersNotDataPids", markersArr)

        // Supported PIDs"""

OLD_E = """            vObj.put("decodedValue", v.decodedValue ?: "")
            vObj.put("respondingCanId", v.respondingCanId ?: "")"""

NEW_E = """            vObj.put("decodedValue", v.decodedValue ?: "")
            vObj.put("dataQuality", v.dataQuality)
            vObj.put("respondingCanId", v.respondingCanId ?: "")"""

OLD_F = """        json.put("validationResults", valArr)"""

NEW_F = """        json.put("validationResults", valArr)
        json.put("positiveReplies", valArr.length())
        json.put("usableValues", _validatedPids.value.count { it.dataQuality == "PLAUSIBLE" })"""

OLD_G = """        sb.append("PID,Name,ShortName,Unit,BitmapSupported,ValidationStatus,DecodedValue,LatencyMs\\n")"""

NEW_G = """        sb.append("PID,Name,ShortName,Unit,BitmapSupported,ValidationStatus,DataQuality,DecodedValue,LatencyMs\\n")"""

OLD_H = """            val valStatus = v?.directStatus?.name ?: "NOT_VALIDATED"
            val decoded = (v?.decodedValue ?: "").replace(",", ";")"""

NEW_H = """            val valStatus = v?.directStatus?.name ?: "NOT_VALIDATED"
            val dataQuality = v?.dataQuality ?: "NOT_VALIDATED"
            val decoded = (v?.decodedValue ?: "").replace(",", ";")"""

OLD_I = """            sb.append("${pid.pid},\\"${pid.name}\\",\\"${pid.shortName}\\",\\"${pid.unit}\\",${pid.supported},$valStatus,\\"$decoded\\",$lat\\n")"""

NEW_I = """            sb.append("${pid.pid},\\"${pid.name}\\",\\"${pid.shortName}\\",\\"${pid.unit}\\",${pid.supported},$valStatus,$dataQuality,\\"$decoded\\",$lat\\n")"""

patch(S, [(OLD_A, NEW_A), (OLD_B, NEW_B), (OLD_C, NEW_C), (OLD_D, NEW_D),
          (OLD_E, NEW_E), (OLD_F, NEW_F), (OLD_G, NEW_G), (OLD_H, NEW_H), (OLD_I, NEW_I)])

P = 'app/src/main/java/com/example/model/PidDefinition.kt'

OLD_J = """            name = "Mode 01 PID $clean",
            shortName = "PID $clean",
            unit = "RAW",
            dataBytes = 1,
            decoderType = DecoderType.RESEARCH_RAW,
            formulaDisplay = "Raw Value",
            supported = isSupported,
            description = "Standard SAE J1979 OBD-II Mode 01 Parameter ($clean)",
            // Conservative explicit interval for uncatalogued discoveries (2026-09-14:
            // no PID may rely on a constructor default any more).
            priority = PollingPriority.MEDIUM,
            defaultIntervalMs = 500L"""

NEW_J = """            name = "Mode 01 PID $clean",
            shortName = "PID $clean",
            unit = "RAW",
            dataBytes = 1,
            decoderType = DecoderType.RESEARCH_RAW,
            formulaDisplay = "Raw Value",
            supported = isSupported,
            isResearch = true,
            description = "Standard SAE J1979 OBD-II Mode 01 Parameter ($clean) whose meaning is " +
                "not catalogued yet, so it is captured as research raw and never presented as a " +
                "known physical value.",
            // Conservative explicit interval for uncatalogued discoveries (2026-09-14:
            // no PID may rely on a constructor default any more). Tightened 2026-09-17:
            // "Apply to Live Polling" adds every discovered PID with enabled = true, and a
            // MEDIUM / 500 ms slot for a channel of unknown meaning only burns bus bandwidth
            // that the FAST channels (rpm, speed, throttle, torque) need for gear and power
            // pairing. Unknown things are recorded slowly and honestly, never polled fast.
            priority = PollingPriority.SLOW,
            defaultIntervalMs = 3000L"""

patch(P, [(OLD_J, NEW_J)])
print('part 3 done')
