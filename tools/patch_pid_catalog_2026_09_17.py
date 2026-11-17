import io, sys

def patch(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        if old == new:
            print('NOOP pair in ' + path + ' :: ' + repr(old[:60]))
            sys.exit(1)
        n = s.count(old)
        if n != 1:
            print('FAIL anchor count=' + str(n) + ' in ' + path + ' :: ' + repr(old[:70]))
            sys.exit(1)
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8').write(s)
    print('OK ' + path + ' (' + str(len(pairs)) + ' anchors)')

NL = chr(10)

def j(*lines):
    return NL.join(lines)

P = 'app/src/main/java/com/example/model/PidDefinition.kt'
pairs = []

pairs.append((
'    TRANSMISSION_GEAR_A4, // SAE J1979 PID 01A4 Actual Gear Ratio / Status',
j('    TRANSMISSION_GEAR_A4, // SAE J1979 PID 01A4 Actual Gear Ratio / Status',
  '    LAMBDA_SENSOR_VOLTAGE,// A/128 lambda + B/128 V - SAE J1979 PID 56-59 (owner run 2026-09-16 showed raw 7F)',
  '    LAMBDA_STFT_PAIR,     // A/128 lambda + (C-128)*100/128 % - SAE J1979 PID 55')))

pairs.append((
j('                id = "0155", service = "01", pid = "55",',
  '                name = "Short Term O2 Trim Bank 1",',
  '                shortName = "ST O2 B1", unit = "%",',
  '                dataBytes = 2, decoderType = DecoderType.FUEL_TRIM,',
  '                description = "Short-term O2 sensor fuel trim, bank 1",'),
j('                id = "0155", service = "01", pid = "55",',
  '                name = "O2 Sensor 1-1 (Lambda + Short Trim)",',
  '                shortName = "L S1-1", unit = "lambda/%",',
  '                dataBytes = 4, decoderType = DecoderType.LAMBDA_STFT_PAIR,',
  '                formulaDisplay = "lambda = ((A * 256) + B) / 32768 ; STFT % = (C - 128) * 100 / 128",',
  '                description = "SAE J1979 PID 55: equivalence ratio plus short-term trim of oxygen sensor 1-1 (4 bytes). Was mislabelled as a 2-byte bank 1 fuel trim.",')))

pairs.append((
j('                id = "0156", service = "01", pid = "56",',
  '                name = "O2 Sensor Voltage (B1S1)",',
  '                shortName = "O2 B1S1", unit = "V",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "Pre-cat O2 sensor voltage (B1S1)",'),
j('                id = "0156", service = "01", pid = "56",',
  '                name = "O2 Sensor 1-1 (Lambda + Voltage)",',
  '                shortName = "L S1-1 V", unit = "lambda/V",',
  '                dataBytes = 2, decoderType = DecoderType.LAMBDA_SENSOR_VOLTAGE,',
  '                formulaDisplay = "lambda = ((A * 256) + B) / 32768 ; V = A / 128",',
  '                description = "SAE J1979 PID 56: lambda and voltage of oxygen sensor 1-1. The owner run 2026-09-16 returned A=7F B=00, i.e. lambda 0.992 and 0.635 V at closed-loop stoichiometry, while RESEARCH_RAW printed the bare byte.",')))

pairs.append((
j('                id = "0157", service = "01", pid = "57",',
  '                name = "O2 Sensor Voltage (B1S2)",',
  '                shortName = "O2 B1S2", unit = "V",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "Post-cat O2 sensor voltage (B1S2)",'),
j('                id = "0157", service = "01", pid = "57",',
  '                name = "O2 Sensor 1-2 (Lambda + Voltage)",',
  '                shortName = "L S1-2", unit = "lambda/V",',
  '                dataBytes = 2, decoderType = DecoderType.LAMBDA_SENSOR_VOLTAGE,',
  '                formulaDisplay = "lambda = ((A * 256) + B) / 32768 ; V = A / 128",',
  '                description = "SAE J1979 PID 57: lambda and voltage of oxygen sensor 1-2.",')))

pairs.append((
j('                id = "0158", service = "01", pid = "58",',
  '                name = "O2 Sensor Voltage (B2S1)",',
  '                shortName = "O2 B2S1", unit = "V",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "Pre-cat O2 sensor voltage (B2S1) - bank 2",'),
j('                id = "0158", service = "01", pid = "58",',
  '                name = "O2 Sensor 1-3 (Lambda + Voltage)",',
  '                shortName = "L S1-3", unit = "lambda/V",',
  '                dataBytes = 2, decoderType = DecoderType.LAMBDA_SENSOR_VOLTAGE,',
  '                formulaDisplay = "lambda = ((A * 256) + B) / 32768 ; V = A / 128",',
  '                description = "SAE J1979 PID 58: lambda and voltage of oxygen sensor 1-3. A 3-cylinder EA211 has ONE bank, so the old bank 2 label was wrong.",')))

pairs.append((
j('                id = "0159", service = "01", pid = "59",',
  '                name = "O2 Sensor Voltage (B2S2)",',
  '                shortName = "O2 B2S2", unit = "V",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "Post-cat O2 sensor voltage (B2S2) - bank 2",'),
j('                id = "0159", service = "01", pid = "59",',
  '                name = "O2 Sensor 1-4 (Lambda + Voltage)",',
  '                shortName = "L S1-4", unit = "lambda/V",',
  '                dataBytes = 2, decoderType = DecoderType.LAMBDA_SENSOR_VOLTAGE,',
  '                formulaDisplay = "lambda = ((A * 256) + B) / 32768 ; V = A / 128",',
  '                description = "SAE J1979 PID 59: lambda and voltage of oxygen sensor 1-4.",')))

pairs.append((
j('                id = "015A", service = "01", pid = "5A",',
  '                name = "Generator Speed (Alternator RPM)",',
  '                shortName = "Alt RPM", unit = "RPM",',
  '                dataBytes = 1, decoderType = DecoderType.RAW_A_KPA,',
  '                description = "Alternator/generator rotation speed",'),
j('                id = "015A", service = "01", pid = "5A",',
  '                name = "Engine Coolant Temperature (PID 5A)",',
  '                shortName = "ECT 5A", unit = "degC",',
  '                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,',
  '                formulaDisplay = "A - 40",',
  '                description = "SAE J1979 PID 5A is engine coolant temperature (A - 40). It was catalogued as Generator Speed decoded in kPa, an invented channel that would have displayed coolant degrees as alternator RPM.",')))

pairs.append((
j('                id = "0165", service = "01", pid = "65",',
  '                name = "Turbocharger Boost Pressure",',
  '                shortName = "Boost", unit = "kPa",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "Manifold absolute pressure after turbocharger (EA211)",'),
j('                id = "0165", service = "01", pid = "65",',
  '                name = "Turbocharger Boost Pressure",',
  '                shortName = "Boost", unit = "kPa",',
  '                dataBytes = 1, decoderType = DecoderType.RAW_A_KPA,',
  '                formulaDisplay = "A (kPa)",',
  '                description = "SAE J1979 PID 65: turbocharger boost pressure, ONE byte in kPa. Catalogued with dataBytes = 2 plus RESEARCH_RAW, so the owner run 2026-09-16 displayed the raw pair 10 10 instead of 16 kPa and a FAST-priority turbo channel produced nothing usable.",')))

pairs.append((
j('                decoderType = DecoderType.TEMP_MINUS_40,',
  '                formulaDisplay = "A - 40",',
  '                description = "Radiator outlet or secondary coolant temperature",'),
j('                decoderType = DecoderType.CATALYST_TEMP,',
  '                dataBytes = 2,',
  '                formulaDisplay = "((A * 256) + B) / 10 - 40",',
  '                description = "SAE J1979 PID 67: engine coolant temperature 2, 2 bytes in 0.1 degC steps with a -40 offset. Catalogued as 1 byte A - 40, which is why the owner run 2026-09-16 could only report implausible raw no data.",')))

pairs.append((
j('                id = "0168", service = "01", pid = "68",',
  '                name = "Intake Air Temperature 2 (Alt)",',
  '                shortName = "IAT 2 Alt", unit = "\u00b0C",',
  '                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,',
  '                description = "Secondary intake air temperature (post-throttle body, EA211)",'),
j('                id = "0168", service = "01", pid = "68",',
  '                name = "Intake Air Temperature 2 (Alt)",',
  '                shortName = "IAT 2 Alt", unit = "\u00b0C",',
  '                dataBytes = 2, decoderType = DecoderType.CATALYST_TEMP,',
  '                formulaDisplay = "((A * 256) + B) / 10 - 40",',
  '                description = "SAE J1979 PID 68: intake air temperature 2, 2 bytes in 0.1 degC steps with a -40 offset (was 1 byte A - 40, hence the implausible-raw verdict in the owner run 2026-09-16).",')))

pairs.append((
j('                id = "017A", service = "01", pid = "7A",',
  '                name = "O2 Sensor Voltage (B4S1)",',
  '                shortName = "O2 B4S1", unit = "V",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "Pre-cat O2 sensor voltage (B4S1) - bank 4",'),
j('                id = "017A", service = "01", pid = "7A",',
  '                name = "Particulate Matter (PM) Sensor [7A]",',
  '                shortName = "PM [7A]", unit = "RAW",',
  '                dataBytes = 9, decoderType = DecoderType.RESEARCH_RAW,',
  '                isResearch = true,',
  '                description = "SAE J1979 PID 7A is the particulate-matter sensor block (9 bytes), not an O2 sensor on bank 4 - a 3-cylinder engine has one bank. The owner run returned 7 bytes (07 00 0E 00 0B 00 00), which matches neither definition, so it stays research raw until validated on the car.",')))

pairs.append((
j('                id = "017F", service = "01", pid = "7F",',
  '                name = "NOx Sensor (post-DPF)",',
  '                shortName = "NOx", unit = "ppm",',
  '                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "NOx concentration after diesel particulate filter",'),
j('                id = "017F", service = "01", pid = "7F",',
  '                name = "Engine Run Time for AECD #13",',
  '                shortName = "AECD 13", unit = "RAW",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                isResearch = true,',
  '                description = "SAE J1979 PID 7F is engine run time for AECD #13, not a post-DPF NOx reading. Not claimed by this car (bit clear in the 0160 block bitmap).",')))

pairs.append((
j('            PidDefinition(',
  '                id = "0180", service = "01", pid = "80",',
  '                name = "Diesel Particulate Filter Temperature",',
  '                shortName = "DPF Temp", unit = "\u00b0C",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "Diesel particulate filter inlet/outlet temperature",',
  '                priority = PollingPriority.SLOW,',
  '                defaultIntervalMs = 3000L',
  '            ),'),
j('            PidDefinition(',
  '                id = "0180", service = "01", pid = "80",',
  '                name = "Supported PIDs [81-A0] (range marker)",',
  '                shortName = "Range 81-A0", unit = "Bitmap",',
  '                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,',
  '                isResearch = true,',
  '                enabled = false,',
  '                description = "SAE J1979 PID 80 is the availability bitmap for PIDs 81-A0, NOT a diesel particulate filter temperature, and never on a petrol engine. Disabled and excluded from discovery since 2026-09-17: the owner export listed it as a supported data PID and decoded the lagged 01A0 bitmap as its value.",',
  '                priority = PollingPriority.SLOW,',
  '                defaultIntervalMs = 3000L',
  '            ),')))

pairs.append((
j('                id = "0181", service = "01", pid = "81",',
  '                name = "DPF Soot Load",',
  '                shortName = "DPF Soot", unit = "%",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "Diesel particulate filter soot load estimate",'),
j('                id = "0181", service = "01", pid = "81",',
  '                name = "Engine Run Time for AECD #1",',
  '                shortName = "AECD 1", unit = "RAW",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                isResearch = true,',
  '                enabled = false,',
  '                description = "SAE J1979 PID 81 is engine run time for AECD #1, not DPF soot load.",')))

pairs.append((
j('                id = "0182", service = "01", pid = "82",',
  '                name = "DPF Ash Load",',
  '                shortName = "DPF Ash", unit = "%",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "Diesel particulate filter ash load estimate",'),
j('                id = "0182", service = "01", pid = "82",',
  '                name = "Engine Run Time for AECD #2",',
  '                shortName = "AECD 2", unit = "RAW",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                isResearch = true,',
  '                enabled = false,',
  '                description = "SAE J1979 PID 82 is engine run time for AECD #2, not DPF ash load.",')))

pairs.append((
j('            PidDefinition(',
  '                id = "0183", service = "01", pid = "83",',
  '                name = "DPF Regeneration Status",',
  '                shortName = "DPF Regen", unit = "",',
  '                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "DPF regeneration status, distance to next regen",',
  '                priority = PollingPriority.SLOW,',
  '                defaultIntervalMs = 3000L',
  '            ),'),
j('            PidDefinition(',
  '                id = "0183", service = "01", pid = "83",',
  '                name = "NOx Sensor [83]",',
  '                shortName = "NOx [83]", unit = "RAW",',
  '                dataBytes = 5, decoderType = DecoderType.RESEARCH_RAW,',
  '                isResearch = true,',
  '                description = "SAE J1979 PID 83 = NOx sensor (5 bytes), not DPF regeneration status. The real 0180-block bitmap of this car (00 24 00 0D) sets this bit, so the Kylaq claims it, but the 2026-09-16 run never validated it because the 0180 answer was consumed by the lagged-bitmap bug. Research raw until a validated response defines the bit layout.",',
  '                priority = PollingPriority.SLOW,',
  '                defaultIntervalMs = 3000L',
  '            ),',
  '            // 0184 Manifold Surface Temperature (SAE J1979)',
  '            PidDefinition(',
  '                id = "0184", service = "01", pid = "84",',
  '                name = "Manifold Surface Temperature",',
  '                shortName = "Manifold T", unit = "\u00b0C",',
  '                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,',
  '                formulaDisplay = "A - 40",',
  '                enabled = false,',
  '                description = "SAE J1979 PID 84: manifold surface temperature. Not claimed by the 0180 bitmap of this car, so it stays disabled unless a run proves otherwise.",',
  '                priority = PollingPriority.SLOW,',
  '                defaultIntervalMs = 3000L',
  '            ),',
  '            // 0185 NOx Reagent System (SAE J1979) - bitmap-supported on this car',
  '            PidDefinition(',
  '                id = "0185", service = "01", pid = "85",',
  '                name = "NOx Reagent System [85]",',
  '                shortName = "Reagent [85]", unit = "RAW",',
  '                dataBytes = 5, decoderType = DecoderType.RESEARCH_RAW,',
  '                isResearch = true,',
  '                description = "SAE J1979 PID 85 = NOx reagent system. It was missing from the catalog entirely. Claimed by the real 0180 bitmap of this car; never validated because of the lagged-bitmap bug. Research raw until captured.",',
  '                priority = PollingPriority.SLOW,',
  '                defaultIntervalMs = 3000L',
  '            ),')))

pairs.append((
j('            PidDefinition(',
  '                id = "0186", service = "01", pid = "86",',
  '                name = "Estimated Fuel Filament Power Degradation",',
  '                shortName = "Fuel Deg", unit = "%",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "Fuel system fuel filament degradation estimate (BOSCH)",',
  '                priority = PollingPriority.SLOW,',
  '                defaultIntervalMs = 3000L',
  '            ),'),
j('            PidDefinition(',
  '                id = "0186", service = "01", pid = "86",',
  '                name = "Particulate Matter (PM) Sensor [86]",',
  '                shortName = "PM [86]", unit = "RAW",',
  '                dataBytes = 9, decoderType = DecoderType.RESEARCH_RAW,',
  '                isResearch = true,',
  '                description = "SAE J1979 PID 86 = particulate-matter sensor (9 bytes), not an estimated fuel filament degradation - no such standard PID exists. Claimed by the real 0180 bitmap of this car (GPF monitoring on a BS6 Phase 2 petrol); never validated because of the lagged-bitmap bug.",',
  '                priority = PollingPriority.SLOW,',
  '                defaultIntervalMs = 3000L',
  '            ),')))

pairs.append((
j('            PidDefinition(',
  '                id = "0187", service = "01", pid = "87",',
  '                name = "Estimated Fuel Injector Correction",',
  '                shortName = "Inj Corr", unit = "%",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                description = "Fuel injector correction factor (BOSCH)",',
  '                priority = PollingPriority.SLOW,',
  '                defaultIntervalMs = 3000L',
  '            ),'),
j('            PidDefinition(',
  '                id = "0187", service = "01", pid = "87",',
  '                name = "Intake Manifold Absolute Pressure [87]",',
  '                shortName = "MAP [87]", unit = "kPa",',
  '                dataBytes = 1, decoderType = DecoderType.RAW_A_KPA,',
  '                formulaDisplay = "A (kPa)",',
  '                enabled = false,',
  '                description = "SAE J1979 PID 87 = intake manifold absolute pressure (1 byte, kPa), not an estimated fuel injector correction. It duplicates PID 0B, so it stays disabled to keep the poll cycle lean. Not claimed by the bitmap of this car.",',
  '                priority = PollingPriority.SLOW,',
  '                defaultIntervalMs = 3000L',
  '            ),')))

pairs.append((
j('                id = "018A", service = "01", pid = "8A",',
  '                name = "Injection Quantity",',
  '                shortName = "Inj Qty", unit = "mm\u00b3",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,'),
j('                id = "018A", service = "01", pid = "8A",',
  '                name = "Vendor-Specific [8A] (not in J1979)",',
  '                shortName = "Vendor [8A]", unit = "RAW",',
  '                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,',
  '                isResearch = true,')))

patch(P, pairs)

DEC = 'app/src/main/java/com/example/protocol/PidDecoder.kt'
new_branches = j(
'            // SAE J1979 PID 56-59: byte A is the equivalence ratio in 1/128 lambda, byte B',
'            // is the sensor voltage in 1/128 V. The owner Kylaq run 2026-09-16 answered',
'            // 7F 00 for PID 56, i.e. lambda 0.992 and 0.635 V at closed-loop stoichiometry,',
'            // while the catalog had it as RESEARCH_RAW so the export printed the bare byte.',
'            DecoderType.LAMBDA_SENSOR_VOLTAGE -> {',
'                if (dataBytes.size < 2) {',
'                    DecodedResult(',
'                        parameterName = pidDef.name,',
'                        numericValue = null,',
'                        displayValue = "NO DATA",',
'                        unit = pidDef.unit,',
'                        rawPayloadHex = rawHex,',
'                        dataBytes = dataBytes,',
'                        isKnown = false',
'                    )',
'                } else {',
'                    val lambdaValue = ((a * 256.0) + b) / 32768.0',
'                    val volts = a / 128.0',
'                    DecodedResult(',
'                        parameterName = pidDef.name,',
'                        numericValue = lambdaValue,',
'                        displayValue = String.format(Locale.US, "\\u03BB %.3f / %.3f V", lambdaValue, volts),',
'                        unit = pidDef.unit,',
'                        rawPayloadHex = rawHex,',
'                        dataBytes = dataBytes,',
'                        isKnown = true',
'                    )',
'                }',
'            }',
'',
'            // SAE J1979 PID 55: A-B is the equivalence ratio (1/128 lambda), C-D is the',
'            // short-term fuel trim of that sensor (1/128 %, offset 128).',
'            DecoderType.LAMBDA_STFT_PAIR -> {',
'                if (dataBytes.size < 4) {',
'                    DecodedResult(',
'                        parameterName = pidDef.name,',
'                        numericValue = null,',
'                        displayValue = "NO DATA",',
'                        unit = pidDef.unit,',
'                        rawPayloadHex = rawHex,',
'                        dataBytes = dataBytes,',
'                        isKnown = false',
'                    )',
'                } else {',
'                    val lambdaValue = ((a * 256.0) + b) / 32768.0',
'                    val stftPct = (c - 128) * 100.0 / 128.0',
'                    DecodedResult(',
'                        parameterName = pidDef.name,',
'                        numericValue = lambdaValue,',
'                        displayValue = String.format(Locale.US, "\\u03BB %.3f / %+.1f %%", lambdaValue, stftPct),',
'                        unit = pidDef.unit,',
'                        rawPayloadHex = rawHex,',
'                        dataBytes = dataBytes,',
'                        isKnown = true',
'                    )',
'                }',
'            }',
'',
'            DecoderType.CUSTOM_EXPRESSION, DecoderType.RESEARCH_RAW -> {')
patch(DEC, [('            DecoderType.CUSTOM_EXPRESSION, DecoderType.RESEARCH_RAW -> {', new_branches)])
print('part 2 done')
