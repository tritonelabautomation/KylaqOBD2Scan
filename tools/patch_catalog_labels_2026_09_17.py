import io, sys, re

P = 'app/src/main/java/com/example/model/PidDefinition.kt'
s = io.open(P, encoding='utf-8').read()

# ---- 1. section headers: rewrite whole lines by PID token -------------------
DASH = '\u2500'
HEADER_NEW = {
    '0155': '0155 O2 Sensor 1-1 (lambda + short trim, J1979)',
    '0156': '0156 O2 Sensor 1-1 (lambda + voltage, J1979)',
    '0157': '0157 O2 Sensor 1-2 (lambda + voltage, J1979)',
    '0158': '0158 O2 Sensor 1-3 (lambda + voltage, J1979)',
    '0159': '0159 O2 Sensor 1-4 (lambda + voltage, J1979)',
    '015A': '015A Engine Coolant Temperature (J1979)',
    '015B': '015B Vendor-specific, no J1979 definition',
    '0178': '0178 Exhaust Gas Temperature Bank 1 (J1979)',
    '0179': '0179 Exhaust Gas Temperature Bank 2 (J1979)',
    '017A': '017A PM Sensor (J1979)',
    '017B': '017B PM Sensor 2 (J1979)',
    '017F': '017F Engine Run Time for AECD #13 (J1979)',
    '0180': '0180 RANGE MARKER - not a data PID (J1979)',
    '0181': '0181 Engine Run Time for AECD #1 (J1979)',
    '0182': '0182 Engine Run Time for AECD #2 (J1979)',
    '0183': '0183 NOx Sensor (J1979) - bitmap-supported on this car',
    '0186': '0186 PM Sensor (J1979) - bitmap-supported on this car',
    '0187': '0187 Intake MAP (J1979, duplicate of 010B)',
    '018A': '018A Vendor-specific, no J1979 definition',
    '018F': '018F Vendor-specific, no J1979 definition',
}

lines = s.split('\n')
replaced = 0
for i, line in enumerate(lines):
    stripped = line.strip()
    if not stripped.startswith('//') or DASH not in stripped:
        continue
    m = re.search(r'\b(01[0-9A-F]{2})\b', stripped)
    if not m:
        continue
    key = m.group(1)
    if key not in HEADER_NEW:
        continue
    indent = line[:len(line) - len(line.lstrip())]
    body = HEADER_NEW[key]
    pad = max(3, 78 - len(indent) - len(body) - 8)
    lines[i] = indent + '// ' + DASH * 3 + ' ' + body + ' ' + DASH * pad
    replaced += 1
s = '\n'.join(lines)
print('headers rewritten: ' + str(replaced))

# ---- 2. entry corrections ---------------------------------------------------
def rep(old, new):
    global s
    n = s.count(old)
    if n != 1:
        print('FAIL count=' + str(n) + ' :: ' + repr(old[:70]))
        sys.exit(1)
    s = s.replace(old, new)

rep("""                id = "0178", service = "01", pid = "78",
                name = "O2 Sensor Voltage (B3S1)",
                shortName = "O2 B3S1", unit = "V",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Pre-cat O2 sensor voltage (B3S1) - bank 3",""",
"""                id = "0178", service = "01", pid = "78",
                name = "Exhaust Gas Temperature Bank 1",
                shortName = "EGT B1", unit = "\u00b0C",
                dataBytes = 2, decoderType = DecoderType.CATALYST_TEMP,
                formulaDisplay = "((A * 256) + B) / 10 - 40",
                description = "SAE J1979 PID 78 is exhaust gas temperature bank 1 in 0.1 degC steps with a -40 offset, not an O2 sensor on a bank 3 that does not exist on a 3-cylinder engine.",""")

rep("""                id = "0179", service = "01", pid = "79",
                name = "O2 Sensor Voltage (B3S2)",
                shortName = "O2 B3S2", unit = "V",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Post-cat O2 sensor voltage (B3S2) - bank 3",""",
"""                id = "0179", service = "01", pid = "79",
                name = "Exhaust Gas Temperature Bank 2",
                shortName = "EGT B2", unit = "\u00b0C",
                dataBytes = 2, decoderType = DecoderType.CATALYST_TEMP,
                formulaDisplay = "((A * 256) + B) / 10 - 40",
                enabled = false,
                description = "SAE J1979 PID 79 is exhaust gas temperature bank 2. This engine has one bank, so it stays disabled unless a run proves the car answers it.",""")

rep("""                id = "017B", service = "01", pid = "7B",
                name = "O2 Sensor Voltage (B4S2)",
                shortName = "O2 B4S2", unit = "V",
                dataBytes = 2, decoderType = DecoderType.RESEARCH_RAW,
                description = "Post-cat O2 sensor voltage (B4S2) - bank 4",""",
"""                id = "017B", service = "01", pid = "7B",
                name = "Particulate Matter (PM) Sensor [7B]",
                shortName = "PM [7B]", unit = "RAW",
                dataBytes = 9, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                description = "SAE J1979 PID 7B is a second particulate-matter sensor block, not an O2 sensor on bank 4. Research raw until a validated response defines the layout.",""")

rep("""                id = "015B", service = "01", pid = "5B",
                name = "Engine Coolant Temperature 3 (Alt)",
                shortName = "Coolant 3B", unit = "\u00b0C",
                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,
                description = "Tertiary engine coolant temperature (Bosch)",""",
"""                id = "015B", service = "01", pid = "5B",
                name = "Vendor-Specific [5B] (not in J1979)",
                shortName = "Vendor [5B]", unit = "RAW",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "PID 5B has no SAE J1979 definition in the tables this project cites. It was catalogued as a tertiary coolant temperature decoded A - 40, which is an invented meaning, so it is now disabled research raw and can never publish a made-up number.",""")

rep("""                id = "018F", service = "01", pid = "8F",
                name = "Engine Oil Temperature 2",
                shortName = "Oil Temp 2", unit = "\u00b0C",
                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,
                description = "Secondary engine oil temperature sensor",""",
"""                id = "018F", service = "01", pid = "8F",
                name = "Vendor-Specific [8F] (not in J1979)",
                shortName = "Vendor [8F]", unit = "RAW",
                dataBytes = 1, decoderType = DecoderType.RESEARCH_RAW,
                isResearch = true,
                enabled = false,
                description = "PID 8F has no SAE J1979 definition in the tables this project cites. It was catalogued as a secondary engine oil temperature decoded A - 40, which is an invented meaning, so it is now disabled research raw instead.",""")

io.open(P, 'w', encoding='utf-8').write(s)
print('OK ' + P)
