#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
2026-09-17 pass B - J1979 correction of the PID catalogue + decoders.

WHY THIS PASS EXISTS
The first pass of this audit (commit 5449e5c) contained a BIT-ARITHMETIC ERROR.
The 0180-block bitmap of the owner run (`00 24 00 0D`) was decoded BY HAND as
PIDs 83 / 85 / 86 ("NOx sensor", "NOx reagent system", "PM sensor"). The real
decode - the one PidDiscoveryDecoder produces, and the one CI proved with three
failing assertions - is 8B, 8E, 9D, 9E. This car never claimed a NOx or PM PID.

Every block re-decoded with the shipped decoder:

    0x00  BE 3E A8 13 -> 01 03 04 05 06 07 0B 0C 0D 0E 0F 11 13 15 1C 1F  (+marker 20)
    0x40  FE D0 84 01 -> 41 42 43 44 45 46 47 49 4A 4C 51 56            (+marker 60)
    0x60  6B 09 00 41 -> 62 63 65 67 68 6D 70 7A                        (+marker 80)
    0x80  00 24 00 0D -> 8B 8E 9D 9E                                    (+marker A0)
    0xA0  14 00 00 00 -> A4 A6                                          (no next block)

So every data PID the export lists is CORRECT; the only invented rows are the
three range markers that were catalogued as data channels: 0160, 0180 and 01A0
("Transmission Sump Temperature", printed -20 degC in the export).

BIGGEST WIN: PID 01A6. The export recorded `41 A6 00 00 86 EB` and the app
printed "Unknown Research PID 01A6". J1979 01A6 is the ODOMETER,
((A*2^24)+(B*2^16)+(C*2^8)+D)/10 km -> 34539/10 = 10279.5 km. A real channel
this car already answers and claims in its 01A0 bitmap, thrown away as raw hex.

Names/formulas verified against a full SAE J1979 / ISO 15031-5 Mode 01 PID table
and cross-checked against the frames in the owner export of 2026-09-16.

Also here: the ten duplicate catalogue ids (defaults list + "additional" list,
the later entry silently winning the map) collapse to one authoritative entry
each, keeping the polling bands the dashboard was tuned on.
"""
import io
import re
import sys

CAT = "app/src/main/java/com/example/model/PidDefinition.kt"
DEC = "app/src/main/java/com/example/protocol/PidDecoder.kt"

BOX = "\u2500"
edits = 0


def load(p):
    return io.open(p, encoding="utf-8").read().split("\n")


def save(p, lines):
    io.open(p, "w", encoding="utf-8").write("\n".join(lines))


def rep1(text, old, new, label):
    global edits
    if new in text and old not in text:
        print("NOOP already applied: %s" % label)
        return text
    n = text.count(old)
    if n != 1:
        print("FAIL %s: anchor count %d" % (label, n))
        sys.exit(1)
    edits += 1
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# block helpers (line based - box-drawing anchors are fragile, field lines are not)
# ---------------------------------------------------------------------------
def find_block(lines, pid_id):
    """Return (start, end) line indexes of the PidDefinition entry with this id.

    start = the 'PidDefinition(' line (or the comment line directly above it),
    end   = the line holding the closing ')' or '),' (inclusive).
    """
    pat = re.compile(r'^\s*id = "%s",' % re.escape(pid_id))
    idx = [i for i, l in enumerate(lines) if pat.match(l)]
    if not idx:
        return None
    i = idx[0]
    s = i
    while s >= 0 and "PidDefinition(" not in lines[s]:
        s -= 1
    if s - 1 >= 0 and lines[s - 1].strip().startswith("//"):
        s -= 1
    e = i
    while e < len(lines) and not re.match(r"\s*\),?\s*$", lines[e]):
        e += 1
    return (s, e, i, len(idx))


def set_fields(lines, pid_id, fields, label):
    """Replace existing `key = ...` lines inside one entry; append missing ones."""
    global edits
    found = find_block(lines, pid_id)
    if found is None:
        print("FAIL %s: no entry for %s" % (label, pid_id))
        sys.exit(1)
    s, e, idline, n = found
    body = lines[idline:e]
    indent = re.match(r"\s*", lines[idline]).group(0)
    consumed = set()
    out = []
    for l in body:
        m = re.match(r"\s*([A-Za-z]+) = ", l)
        key = m.group(1) if m else None
        # 'dataBytes = 2, decoderType = X,' carries two keys on one line
        m2 = re.match(r"\s*dataBytes = \d+, decoderType = ", l)
        if m2 and ("dataBytes" in fields or "decoderType" in fields):
            db = fields.get("dataBytes")
            dt = fields.get("decoderType")
            cur_db = re.search(r"dataBytes = (\d+)", l).group(1)
            cur_dt = re.search(r"decoderType = ([A-Za-z_.]+)", l).group(1)
            new_db = str(db) if db is not None else cur_db
            new_dt = dt if dt is not None else cur_dt
            tail = "," if l.rstrip().endswith(",") else ""
            out.append("%sdataBytes = %s, decoderType = %s%s" % (indent, new_db, new_dt, tail))
            consumed.add("dataBytes")
            consumed.add("decoderType")
            continue
        if key in fields and key not in consumed:
            val = fields[key]
            tail = "," if l.rstrip().endswith(",") else ""
            out.append('%s%s = %s%s' % (indent, key, val, tail))
            consumed.add(key)
            continue
        out.append(l)
    missing = [(k, v) for k, v in fields.items() if k not in consumed]
    if missing:
        # insert before the last body line (the closing paren lives at lines[e])
        ins = []
        for k, v in missing:
            ins.append('%s%s = %s,' % (indent, k, v))
        # the previous last field must end with a comma-less line only if it was the
        # final argument; adding new args after it needs a comma on it.
        if out and not out[-1].rstrip().endswith(","):
            out[-1] = out[-1].rstrip() + ","
        else:
            ins[-1] = ins[-1].rstrip(",")
        out.extend(ins)
    edits += 1
    return lines[:idline] + out + lines[e:]


def set_header(lines, pid_id, text, label):
    """Rewrite the `// ─── 01XX ...` comment above one entry, keeping its width."""
    global edits
    found = find_block(lines, pid_id)
    if found is None:
        print("FAIL %s: no entry for %s" % (label, pid_id))
        sys.exit(1)
    s, e, idline, n = found
    # the comment is the line above 'PidDefinition('
    c = s
    while "PidDefinition(" not in lines[c]:
        c += 1
    c -= 1
    if c < 0 or not lines[c].strip().startswith("//"):
        print("FAIL %s: no comment line above entry" % label)
        sys.exit(1)
    indent = re.match(r"\s*", lines[c]).group(0)
    body = "%s 01%s %s " % (BOX * 3, pid_id[2:], text)
    total = len(indent) + len(body)
    pad = max(3, 78 - total)
    lines[c] = indent + "// " + body + BOX * pad
    edits += 1
    return lines


def insert_before_entry(lines, pid_id, new_lines, label):
    """Insert `new_lines` above the comment/PidDefinition block of one entry."""
    global edits
    found = find_block(lines, pid_id)
    if found is None:
        print("FAIL %s: no entry for %s" % (label, pid_id))
        sys.exit(1)
    s = found[0]
    edits += 1
    return lines[:s] + new_lines + lines[s:]


def delete_entry_by_index(lines, pid_id, occurrence, label):
    """Delete the n-th (0-based) entry with this id."""
    global edits
    pat = re.compile(r'^\s*id = "%s",' % re.escape(pid_id))
    idx = [i for i, l in enumerate(lines) if pat.match(l)]
    if len(idx) <= occurrence:
        print("NOOP already deleted: %s" % label)
        return lines
    i = idx[occurrence]
    s = i
    while s >= 0 and "PidDefinition(" not in lines[s]:
        s -= 1
    if s - 1 >= 0 and lines[s - 1].strip().startswith("//"):
        s -= 1
    e = i
    while e < len(lines) and not re.match(r"\s*\),?\s*$", lines[e]):
        e += 1
    blk = "\n".join(lines[s:e + 1])
    if blk.count("PidDefinition(") != 1:
        print("FAIL %s: block spans several definitions" % label)
        sys.exit(1)
    closed = lines[e].rstrip().endswith(",")
    rest = lines[:s] + lines[e + 1:]
    if not closed:
        j = s - 1
        while j >= 0 and not rest[j].strip():
            j -= 1
        if rest[j].rstrip().endswith(","):
            rest[j] = rest[j].rstrip()[:-1]
        else:
            print("FAIL %s: cannot repair list terminator" % label)
            sys.exit(1)
    edits += 1
    print("deleted duplicate %s (%d lines)" % (label, e - s + 1))
    return rest


def delete_entry(lines, pid_id, label):
    """Delete the FIRST entry with this id (the defaults list copy)."""
    global edits
    found = find_block(lines, pid_id)
    if found is None:
        print("NOOP already deleted: %s" % label)
        return lines
    s, e, idline, n = found
    blk = "\n".join(lines[s:e + 1])
    if blk.count("PidDefinition(") != 1:
        print("FAIL %s: block spans several definitions" % label)
        sys.exit(1)
    closed = lines[e].rstrip().endswith(",")
    rest = lines[:s] + lines[e + 1:]
    if not closed:
        # it was the last element of its list - the new last element must lose its comma
        j = s - 1
        while j >= 0 and not rest[j].strip():
            j -= 1
        if rest[j].rstrip().endswith(","):
            rest[j] = rest[j].rstrip()[:-1]
        else:
            print("FAIL %s: cannot repair list terminator" % label)
            sys.exit(1)
    edits += 1
    print("deleted %s (%d lines, was %s)" % (label, e - s + 1, "mid-list" if closed else "list tail"))
    return rest


# ===========================================================================
# 1. DecoderType enum
# ===========================================================================
cat = io.open(CAT, encoding="utf-8").read()
cat = rep1(
    cat,
    "    LAMBDA_SENSOR_VOLTAGE,// A/128 lambda + B/128 V - SAE J1979 PID 56-59 (owner run 2026-09-16 showed raw 7F)\n"
    "    LAMBDA_STFT_PAIR,     // A/128 lambda + (C-128)*100/128 % - SAE J1979 PID 55\n",
    "    LAMBDA_2B,            // ((A*256)+B)/32768 lambda, two bytes - J1979 PID 24-2B / 34-3B lambda half\n"
    "    O2_TRIM_PAIR_2B,      // (A-128)*100/128 % and (B-128)*100/128 % - J1979 PID 55-58 secondary O2 trims\n"
    "    ODOMETER_4B,          // ((A*2^24)+(B*2^16)+(C*2^8)+D)/10 km - J1979 PID A6 (car answered 00 00 86 EB = 10279.5 km)\n",
    "enum decoder types",
)
cat = cat.replace("DecoderType.LAMBDA_SENSOR_VOLTAGE", "DecoderType.LAMBDA_2B")
cat = cat.replace("DecoderType.LAMBDA_STFT_PAIR", "DecoderType.O2_TRIM_PAIR_2B")
io.open(CAT, "w", encoding="utf-8").write(cat)

dec = io.open(DEC, encoding="utf-8").read()
dec = rep1(
    dec,
    "            // SAE J1979 PID 56-59: byte A is the equivalence ratio in 1/128 lambda, byte B\n"
    "            // is the sensor voltage in 1/128 V. The owner Kylaq run 2026-09-16 answered\n"
    "            // 7F 00 for PID 56, i.e. lambda 0.992 and 0.635 V at closed-loop stoichiometry,\n"
    "            // while the catalog had it as RESEARCH_RAW so the export printed the bare byte.\n"
    "            DecoderType.LAMBDA_SENSOR_VOLTAGE -> {",
    "            // Two-byte equivalence ratio, ((A*256)+B)/32768 - the layout J1979 uses for the\n"
    "            // lambda half of PID 34-3B (O2 sensor n: lambda + current) and 24-2B (lambda +\n"
    "            // voltage). The owner Kylaq run 2026-09-16 answered `41 56 7F 00`; read as lambda\n"
    "            // that is 0.992 at closed-loop stoichiometry, which independently agrees with PID\n"
    "            // 44 (commanded equivalence ratio) = 1.000 in the same run. Two earlier revisions\n"
    "            // of this code printed the bare byte 7F, then invented \"0.633 V\" out of byte A -\n"
    "            // the frame contains no voltage field, so none is displayed any more.\n"
    "            DecoderType.LAMBDA_2B -> {",
    "decoder lambda comment",
)
dec = rep1(
    dec,
    "                    val lambdaValue = ((a * 256.0) + b) / 32768.0\n"
    "                    val volts = a / 128.0\n"
    "                    DecodedResult(\n"
    "                        parameterName = pidDef.name,\n"
    "                        numericValue = lambdaValue,\n"
    '                        displayValue = String.format(Locale.US, "\\u03BB %.3f / %.3f V", lambdaValue, volts),',
    "                    val lambdaValue = ((a * 256.0) + b) / 32768.0\n"
    "                    DecodedResult(\n"
    "                        parameterName = pidDef.name,\n"
    "                        numericValue = lambdaValue,\n"
    '                        displayValue = String.format(Locale.US, "\\u03BB %.3f", lambdaValue),',
    "decoder lambda display",
)
dec = rep1(
    dec,
    "            // SAE J1979 PID 55: A-B is the equivalence ratio (1/128 lambda), C-D is the\n"
    "            // short-term fuel trim of that sensor (1/128 %, offset 128).\n"
    "            DecoderType.LAMBDA_STFT_PAIR -> {\n"
    "                if (dataBytes.size < 4) {\n"
    "                    DecodedResult(\n"
    "                        parameterName = pidDef.name,\n"
    "                        numericValue = null,\n"
    '                        displayValue = "NO DATA",\n'
    "                        unit = pidDef.unit,\n"
    "                        rawPayloadHex = rawHex,\n"
    "                        dataBytes = dataBytes,\n"
    "                        isKnown = false\n"
    "                    )\n"
    "                } else {\n"
    "                    val lambdaValue = ((a * 256.0) + b) / 32768.0\n"
    "                    val stftPct = (c - 128) * 100.0 / 128.0\n"
    "                    DecodedResult(\n"
    "                        parameterName = pidDef.name,\n"
    "                        numericValue = lambdaValue,\n"
    '                        displayValue = String.format(Locale.US, "\\u03BB %.3f / %+.1f %%", lambdaValue, stftPct),\n'
    "                        unit = pidDef.unit,\n"
    "                        rawPayloadHex = rawHex,\n"
    "                        dataBytes = dataBytes,\n"
    "                        isKnown = true\n"
    "                    )\n"
    "                }\n"
    "            }\n",
    "            // SAE J1979 PID 55-58: secondary oxygen-sensor fuel trims, two bytes, each\n"
    "            // (X - 128) * 100 / 128 percent. 55/56 are the short/long term trim of bank 1 +\n"
    "            // bank 3, 57/58 of bank 2 + bank 4. The owner run answered `41 55 80 80` =\n"
    "            // +0.0 % / +0.0 % (closed loop, post-cat trims at zero) and `41 56 7F 00` =\n"
    "            // -0.8 % / -100.0 %, the second byte being the no-sensor sentinel of the bank\n"
    "            // this 3-cylinder engine does not have.\n"
    "            DecoderType.O2_TRIM_PAIR_2B -> {\n"
    "                if (dataBytes.size < 2) {\n"
    "                    DecodedResult(\n"
    "                        parameterName = pidDef.name,\n"
    "                        numericValue = null,\n"
    '                        displayValue = "NO DATA",\n'
    "                        unit = pidDef.unit,\n"
    "                        rawPayloadHex = rawHex,\n"
    "                        dataBytes = dataBytes,\n"
    "                        isKnown = false\n"
    "                    )\n"
    "                } else {\n"
    "                    val trimA = (a - 128) * 100.0 / 128.0\n"
    "                    val trimB = (b - 128) * 100.0 / 128.0\n"
    '                    // -100 % is the ECU saying "that sensor does not exist". Publishing it as\n'
    "                    // a trim value would be a fake number on a single-bank engine.\n"
    "                    if (trimA <= -99.99 || trimB <= -99.99) {\n"
    "                        DecodedResult(\n"
    "                            parameterName = pidDef.name,\n"
    "                            numericValue = null,\n"
    '                            displayValue = "Not available",\n'
    "                            unit = pidDef.unit,\n"
    "                            rawPayloadHex = rawHex,\n"
    "                            dataBytes = dataBytes,\n"
    "                            isKnown = false\n"
    "                        )\n"
    "                    } else {\n"
    "                        DecodedResult(\n"
    "                            parameterName = pidDef.name,\n"
    "                            numericValue = trimA,\n"
    '                            displayValue = String.format(Locale.US, "%+.1f %% / %+.1f %%", trimA, trimB),\n'
    "                            unit = pidDef.unit,\n"
    "                            rawPayloadHex = rawHex,\n"
    "                            dataBytes = dataBytes,\n"
    "                            isKnown = true\n"
    "                        )\n"
    "                    }\n"
    "                }\n"
    "            }\n"
    "\n"
    "            // SAE J1979 PID A6: odometer, ((A*2^24)+(B*2^16)+(C*2^8)+D)/10 km. The owner run\n"
    "            // recorded `41 A6 00 00 86 EB` and the app printed \"Unknown Research PID 01A6\".\n"
    "            // 34539 / 10 = 10279.5 km - a real channel, already answered, thrown away as raw\n"
    "            // hex. Sanity gate: 0 km and anything above 2 000 000 km are rejected as sentinel\n"
    "            // or garbage instead of being displayed.\n"
    "            DecoderType.ODOMETER_4B -> {\n"
    "                if (dataBytes.size < 4) {\n"
    "                    DecodedResult(\n"
    "                        parameterName = pidDef.name,\n"
    "                        numericValue = null,\n"
    '                        displayValue = "NO DATA",\n'
    "                        unit = pidDef.unit,\n"
    "                        rawPayloadHex = rawHex,\n"
    "                        dataBytes = dataBytes,\n"
    "                        isKnown = false\n"
    "                    )\n"
    "                } else {\n"
    "                    val raw = (a.toLong() shl 24) or (b.toLong() shl 16) or\n"
    "                        (c.toLong() shl 8) or d.toLong()\n"
    "                    val km = raw / 10.0\n"
    "                    if (raw <= 0L || km > 2_000_000.0) {\n"
    "                        DecodedResult(\n"
    "                            parameterName = pidDef.name,\n"
    "                            numericValue = null,\n"
    '                            displayValue = "Not available",\n'
    "                            unit = pidDef.unit,\n"
    "                            rawPayloadHex = rawHex,\n"
    "                            dataBytes = dataBytes,\n"
    "                            isKnown = false\n"
    "                        )\n"
    "                    } else {\n"
    "                        DecodedResult(\n"
    "                            parameterName = pidDef.name,\n"
    "                            numericValue = km,\n"
    '                            displayValue = String.format(Locale.US, "%.1f", km),\n'
    "                            unit = pidDef.unit,\n"
    "                            rawPayloadHex = rawHex,\n"
    "                            dataBytes = dataBytes,\n"
    "                            isKnown = true\n"
    "                        )\n"
    "                    }\n"
    "                }\n"
    "            }\n",
    "decoder trim pair + odometer",
)
io.open(DEC, "w", encoding="utf-8").write(dec)

# ===========================================================================
# 2. Catalogue entries
# ===========================================================================
L = load(CAT)

# --- 2a. collapse the ten duplicate ids first: the defaults-list copies are
#         dead code (the later "additional" entry wins the map), and deleting
#         them is what makes set_fields() below address the surviving entry.
DUPES = ["0107", "010E", "0123", "010A", "0144", "0143", "0145", "015D", "012F", "01A6"]
for d in DUPES:
    L = delete_entry_by_index(L, d, 0, "defaults " + d)

# keep the polling bands the dashboard was tuned on (the defaults copies carried
# them; the surviving "additional" copies had drifted)
BANDS = {
    "0107": ("PollingPriority.MEDIUM", "1200L"),
    "010A": ("PollingPriority.MEDIUM", "500L"),
    "010E": ("PollingPriority.MEDIUM", "500L"),
    "0123": ("PollingPriority.MEDIUM", "500L"),
    "012F": ("PollingPriority.SLOW", "3000L"),
    "0143": ("PollingPriority.MEDIUM", "500L"),
    "0144": ("PollingPriority.MEDIUM", "500L"),
    "0145": ("PollingPriority.FAST", "150L"),
    "015D": ("PollingPriority.MEDIUM", "500L"),
}
for pid, (prio, interval) in BANDS.items():
    L = set_fields(L, pid, {"priority": prio, "defaultIntervalMs": interval}, pid + " band")

D = lambda s: '"%s"' % s
B = lambda v: str(v)

# --- 0155 / 0156 / 0157 / 0158 / 0159: secondary O2 trims, not lambda sensors
for pid, term, banks, claimed in (
    ("0155", "Short Term", "1+3", True),
    ("0156", "Long Term", "1+3", True),
    ("0157", "Short Term", "2+4", False),
    ("0158", "Long Term", "2+4", False),
    ("0159", "Long Term", "2+4", False),
):
    n = pid[2:]
    tail = (
        " The owner run of 2026-09-16 answered it: `41 55 80 80` = +0.0 % / +0.0 %%, the post-cat trims of a closed-loop engine at stoichiometry."
        if pid == "0155"
        else (
            " The owner run answered `41 56 7F 00` = -0.8 %%, and the second byte is the -100 %% no-sensor sentinel of the bank a 3-cylinder does not have, which the decoder suppresses instead of printing."
            if pid == "0156"
            else " Not claimed by this car (bit clear in the 0140 bitmap FE D0 84 01)."
        )
    )
    L = set_fields(L, pid, {
        "name": D("Secondary O2 Sensor %s Fuel Trim (Bank %s)" % (term, banks)),
        "shortName": D("%s O2 B%s" % ("ST" if term.startswith("Short") else "LT", banks)),
        "unit": D("%"),
        "dataBytes": 2,
        "decoderType": "DecoderType.O2_TRIM_PAIR_2B",
        "formulaDisplay": D("(A - 128) * 100 / 128 % ; (B - 128) * 100 / 128 %"),
        "description": D(
            "SAE J1979 PID %s = %s secondary oxygen sensor fuel trim, bank %s, two bytes of (X - 128) * 100 / 128 %%. "
            "It is NOT an oxygen sensor lambda/voltage channel - a revision of this audit mislabelled the whole 55-59 range that way and then invented a sensor voltage out of byte A.%s"
            % (n, term.lower(), banks, tail)
        ),
    }, pid)
    L = set_header(L, pid, "%s secondary O2 trim, bank %s" % (term.lower(), banks), pid)

# --- 015A relative accelerator pedal position
L = set_fields(L, "015A", {
    "name": D("Relative Accelerator Pedal Position"),
    "shortName": D("Pedal Rel"),
    "unit": D("%"),
    "dataBytes": 1,
    "decoderType": "DecoderType.PERCENT_255",
    "formulaDisplay": D("A * 100 / 255"),
    "enabled": "false",
    "description": D(
        "SAE J1979 PID 5A = RELATIVE accelerator pedal position, A * 100 / 255 %%. This entry has now been wrong twice: "
        "first as 'Generator Speed (Alternator RPM)' decoded in kPa, then as a second engine coolant temperature decoded A - 40. "
        "Neither meaning exists in J1979. The bit is clear in this car's 0140 bitmap, so no frame has ever validated it - "
        "the spec name is recorded and the entry stays disabled until the car answers."
    ),
}, "015A")
L = set_header(L, "015A", "Relative accelerator pedal position (J1979)", "015A")

# --- 015B hybrid battery pack remaining life
L = set_fields(L, "015B", {
    "name": D("Hybrid Battery Pack Remaining Life"),
    "shortName": D("Hyb Batt"),
    "unit": D("%"),
    "dataBytes": 1,
    "decoderType": "DecoderType.PERCENT_255",
    "formulaDisplay": D("A * 100 / 255"),
    "enabled": "false",
    "isResearch": "false",
    "description": D(
        "SAE J1979 PID 5B = remaining life of a hybrid battery pack, A * 100 / 255 %%. It was catalogued here as a third "
        "engine coolant temperature decoded A - 40, and then as 'vendor specific, not in J1979' - both wrong. The Kylaq 1.0 TSI "
        "has no hybrid pack and the bit is clear in the 0140 bitmap, so the entry stays disabled: a petrol car must never show a "
        "hybrid-battery number."
    ),
}, "015B")
L = set_header(L, "015B", "Hybrid battery pack life (disabled: no pack)", "015B")

# --- 015F emission requirements enum (was 'engine oil life')
L = set_fields(L, "015F", {
    "name": D("Emission Requirements (Design Standard)"),
    "shortName": D("Emiss Std"),
    "unit": D(""),
    "dataBytes": 1,
    "decoderType": "DecoderType.RESEARCH_RAW",
    "formulaDisplay": D("enum: 1=OBD-II(CARB) 2=OBD 3=OBD+OBD-II 4=OBD-I 5=not compliant 6=EOBD 7=OBD-II+EOBD"),
    "isResearch": "true",
    "enabled": "false",
    "description": D(
        "SAE J1979 PID 5F = an ENUM of the emission standard the vehicle was designed to meet, not a percentage. It was "
        "catalogued as 'Engine Oil Life Remaining' decoded A * 100 / 255 %%, which would have printed the standard code as a "
        "fake oil-life number. This car DOES claim the PID (bit set in the 0140 bitmap FE D0 84 01), so the enum table has to be "
        "written and unit-tested before it is enabled; until then it is disabled research raw."
    ),
}, "015F")
L = set_header(L, "015F", "Emission requirements enum (NOT oil life)", "015F")

# --- 0165 auxiliary input/output status (was 'turbocharger boost pressure')
L = set_fields(L, "0165", {
    "name": D("Auxiliary Input / Output Status"),
    "shortName": D("Aux IO"),
    "unit": D(""),
    "dataBytes": 2,
    "decoderType": "DecoderType.RESEARCH_RAW",
    "formulaDisplay": D("bitmap - layout not established"),
    "isResearch": "true",
    "enabled": "false",
    "priority": "PollingPriority.SLOW",
    "defaultIntervalMs": "3000L",
    "description": D(
        "SAE J1979 PID 65 = 'auxiliary input / output supported', a two-byte bitmap. It is NOT turbocharger boost pressure and "
        "not one byte of kPa. The owner run answered `41 65 10 10`; read as boost that is 16 kPa, plausible for a warm-idle "
        "manifold but resting on a name the standard does not use, so nothing is published until a load sweep proves the layout. "
        "The real pressure channels on this car stay PID 0B (MAP) and PID 6F/70 (compressor inlet / boost control). Before this "
        "pass it was dataBytes = 2 + RESEARCH_RAW at FAST priority, polling an unknown bitmap 6 times a second."
    ),
}, "0165")
L = set_header(L, "0165", "Auxiliary input/output bitmap (boost claim withdrawn)", "0165")

# --- 017A / 017B DPF temperature / pressure (diesel blocks, never claimed)
L = set_fields(L, "017A", {
    "name": D("DPF Temperature [7A]"),
    "shortName": D("DPF T"),
    "unit": D("degC"),
    "enabled": "false",
    "description": D(
        "J1979 lists PID 7A as a diesel particulate filter temperature block (9 bytes). The owner run returned 7 bytes "
        "(07 00 0E 00 0B 00 00) from a PETROL car with a three-way catalyst, so neither the byte count nor the meaning is "
        "established. Research raw, nothing published, entry disabled. Previously catalogued as an O2 sensor on bank 4 - a "
        "3-cylinder engine has one bank."
    ),
}, "017A")
L = set_header(L, "017A", "DPF temperature (diesel block, unproven here)", "017A")

L = set_fields(L, "017B", {
    "name": D("DPF Pressure [7B]"),
    "shortName": D("DPF P"),
    "unit": D("kPa"),
    "enabled": "false",
    "description": D(
        "J1979 lists PID 7B as a diesel particulate filter pressure block (9 bytes) - not an O2 sensor on bank 4 and not a "
        "PM soot/ash loading. This petrol car never claimed the bit. Research raw until a validated response defines the layout."
    ),
}, "017B")
L = set_header(L, "017B", "DPF pressure (diesel block, never claimed)", "017B")

# --- 0186 PM sensor: withdraw the false 'claimed by this car' statement
L = set_fields(L, "0186", {
    "dataBytes": 5,
    "enabled": "false",
    "description": D(
        "SAE J1979 PID 86 = particulate-matter sensor (5 bytes), not an estimated fuel filament degradation - no such standard "
        "PID exists. NOT claimed by this car: the real 0180 bitmap of the 2026-09-16 run is `00 24 00 0D`, which sets PID 8B, "
        "8E, 9D and 9E only. An earlier revision of this audit mis-decoded that bitmap BY HAND as 83/85/86 and reported three "
        "NOx/PM channels as 'hidden by a bug'; that claim is withdrawn - the car never claimed them."
    ),
}, "0186")
L = set_header(L, "0186", "PM sensor (J1979, never claimed by this car)", "0186")

# --- 0187 intake MAP: the car ANSWERED it even though the bitmap is silent
L = set_fields(L, "0187", {
    "dataBytes": 5,
    "enabled": "true",
    "isResearch": "true",
    "description": D(
        "SAE J1979 PID 87 = intake manifold absolute pressure (a 5-byte block in the extended tables), not an estimated fuel "
        "injector correction. This car ANSWERED it: the 2026-09-16 export records `41 87 11` during validation, a direct "
        "positive reply, even though the 0180 bitmap `00 24 00 0D` does not claim it - the bitmap understates what the ECU "
        "will answer. 11 cannot be kPa of manifold pressure at warm idle and 11 - 40 = -29 degC is not a plausible temperature "
        "either, so the first byte is decoded as kPa and flagged research: the layout needs a load sweep before this feeds "
        "anything."
    ),
}, "0187")
L = set_header(L, "0187", "Intake MAP (answered by the car, bitmap silent)", "0187")

# --- 018B: honest about the conflicting evidence
L = set_fields(L, "018B", {
    "isResearch": "true",
    "description": D(
        "The J1979 tables this project cites list PID 8B as 'diesel aftertreatment' (7 bytes); the value this car returns is a "
        "single byte that the export printed as 31.8 %% under A * 100 / 255. A petrol Kylaq has no diesel aftertreatment block, "
        "so the vendor meaning (fuel pump command, low-pressure circuit) is kept - but it is flagged research, because the "
        "evidence for it is a plausible number, not a specification. Claimed by the 0180 bitmap `00 24 00 0D`."
    ),
}, "018B")

# --- 01A0 range marker (was 'Transmission Sump Temperature', printed -20 degC)
L = set_fields(L, "01A0", {
    "name": D("Mode 01 PID A0 - range marker (PIDs supported A1-C0)"),
    "shortName": D("Range A1-C0"),
    "unit": D(""),
    "dataBytes": 4,
    "decoderType": "DecoderType.RESEARCH_RAW",
    "isResearch": "true",
    "enabled": "false",
    "description": D(
        "SAE J1979 PID A0 is the availability bitmap for PID A1 to C0 - the same kind of row as PID 00/20/40/60/80. It is NOT a "
        "transmission sump temperature. Catalogued as TEMP_MINUS_40 it printed '-20 degC' in the owner export of 2026-09-16: a "
        "fabricated gearbox oil temperature from a car that never reported one. Disabled here and excluded from the discovery "
        "list by PidDiscoveryDecoder.decodeSupportedPids."
    ),
}, "01A0")
L = set_header(L, "01A0", "Range marker: PIDs supported [A1-C0]", "01A0")

# --- 01A6 ODOMETER
L = set_fields(L, "01A6", {
    "name": D("Odometer"),
    "shortName": D("Odo"),
    "unit": D("km"),
    "dataBytes": 4,
    "decoderType": "DecoderType.ODOMETER_4B",
    "formulaDisplay": D("((A * 2^24) + (B * 2^16) + (C * 2^8) + D) / 10"),
    "isResearch": "false",
    "enabled": "true",
    "description": D(
        "SAE J1979 PID A6 = the ODOMETER in km at 0.1 km resolution. The owner run of 2026-09-16 recorded `41 A6 00 00 86 EB` "
        "and the app printed 'Unknown Research PID 01A6'; the frame is 34539 / 10 = 10279.5 km. The car claims it in the 01A0 "
        "bitmap `14 00 00 00` (which sets A4 and A6 only), so this is a live channel, not research. The VIN is not in Mode 01 at "
        "all - it is Mode 09 PID 0902 - and a duplicate catalogue entry used to mislabel this PID as a partial VIN."
    ),
}, "01A6")


# --- 2c. PIDs this car claims or answers that the catalogue never had
NEW_88_8A_8E = [
    "            // " + BOX * 3 + " 0188 SCR induce system (J1979, diesel-only) " + BOX * 14,
    "            PidDefinition(",
    '                id = "0188", service = "01", pid = "88",',
    '                name = "SCR Induce System [88]",',
    '                shortName = "SCR Induce", unit = "",',
    "                dataBytes = 13, decoderType = DecoderType.RESEARCH_RAW,",
    "                isResearch = true,",
    "                enabled = false,",
    '                description = "SAE J1979 PID 88 = selective catalytic reduction induce system (13 bytes), a diesel/AdBlue channel. Not claimed by this petrol car.",',
    "                priority = PollingPriority.SLOW,",
    "                defaultIntervalMs = 3000L",
    "            ),",
    "            // " + BOX * 3 + " 018E Engine friction percent torque - CLAIMED by this car " + BOX * 3,
    "            PidDefinition(",
    '                id = "018E", service = "01", pid = "8E",',
    '                name = "Engine Friction - Percent Torque",',
    '                shortName = "Friction %", unit = "%",',
    "                dataBytes = 1, decoderType = DecoderType.TORQUE_PCT,",
    '                formulaDisplay = "A - 125",',
    "                isResearch = true,",
    '                description = "SAE J1979 PID 8E = engine friction percent torque, one byte, A - 125 %%, range -125..130. This car CLAIMS it (bit set in the 0180 bitmap `00 24 00 0D`) and the 2026-09-16 run never validated it because the catalogue had no entry for it at all, so the discovery sweep had nothing to TX. With PID 61 (driver demand) and PID 62 (actual torque) it closes the torque balance: demand - friction - accessory load = wheel torque, which is exactly what the power model needs. Highest-value gap in the catalogue as of this pass.",',
    "                priority = PollingPriority.MEDIUM,",
    "                defaultIntervalMs = 500L",
    "            ),",
]
L = insert_before_entry(L, "018B", NEW_88_8A_8E, "new 0188 + 018E")

L = set_fields(L, "018A", {
    "name": D("Engine Run Time for AECD #16-#20"),
    "shortName": D("AECD 16-20"),
    "unit": D("s"),
    "dataBytes": 21,
    "decoderType": "DecoderType.RESEARCH_RAW",
    "formulaDisplay": D("21-byte AECD run time block"),
    "isResearch": "true",
    "enabled": "false",
    "priority": "PollingPriority.SLOW",
    "defaultIntervalMs": "3000L",
    "description": D(
        "SAE J1979 PID 8A = engine run time for auxiliary emission control devices #16 to #20 (21 bytes). It was catalogued "
        "here as a vendor-specific 'actual fuel injection quantity per stroke (mm3)' polled at MEDIUM priority / 500 ms - an "
        "invented meaning requested twice a second. Not claimed by this car."
    ),
}, "018A")
L = set_header(L, "018A", "Run time for AECD #16-#20 (J1979)", "018A")

NEW_9A_9E = [
    "            // " + BOX * 3 + " 019A Hybrid / EV vehicle system data (J1979) " + BOX * 15,
    "            PidDefinition(",
    '                id = "019A", service = "01", pid = "9A",',
    '                name = "Hybrid / EV Vehicle System Data [9A]",',
    '                shortName = "Hybrid Data", unit = "",',
    "                dataBytes = 7, decoderType = DecoderType.RESEARCH_RAW,",
    "                isResearch = true,",
    "                enabled = false,",
    '                description = "SAE J1979 PID 9A = hybrid / EV vehicle system data (7 bytes). Not claimed by this petrol car.",',
    "                priority = PollingPriority.SLOW,",
    "                defaultIntervalMs = 3000L",
    "            ),",
    "            // " + BOX * 3 + " 019B Diesel exhaust fluid sensor data (J1979) " + BOX * 12,
    "            PidDefinition(",
    '                id = "019B", service = "01", pid = "9B",',
    '                name = "Diesel Exhaust Fluid Sensor Data [9B]",',
    '                shortName = "DEF Data", unit = "",',
    "                dataBytes = 7, decoderType = DecoderType.RESEARCH_RAW,",
    "                isResearch = true,",
    "                enabled = false,",
    '                description = "SAE J1979 PID 9B = diesel exhaust fluid (AdBlue) sensor data (7 bytes). Not claimed by this petrol car.",',
    "                priority = PollingPriority.SLOW,",
    "                defaultIntervalMs = 3000L",
    "            ),",
    "            // " + BOX * 3 + " 019C O2 sensor data block (J1979, 17 bytes) " + BOX * 14,
    "            PidDefinition(",
    '                id = "019C", service = "01", pid = "9C",',
    '                name = "Oxygen Sensor Data [9C]",',
    '                shortName = "O2 Data", unit = "",',
    "                dataBytes = 17, decoderType = DecoderType.RESEARCH_RAW,",
    "                isResearch = true,",
    "                enabled = false,",
    '                description = "J1979 lists PID 9C as a 17-byte oxygen sensor data block (multi-sensor, WWH-OBD era). Not claimed by this car\'s 0180 bitmap. A 17-byte block cannot be split into honest per-sensor values without a validated frame, so it stays disabled research raw.",',
    "                priority = PollingPriority.SLOW,",
    "                defaultIntervalMs = 3000L",
    "            ),",
    "            // " + BOX * 3 + " 019E Engine exhaust flow rate - CLAIMED and ANSWERED " + BOX * 7,
    "            PidDefinition(",
    '                id = "019E", service = "01", pid = "9E",',
    '                name = "Engine Exhaust Flow Rate",',
    '                shortName = "Exh Flow", unit = "kg/h",',
    "                dataBytes = 5, decoderType = DecoderType.RESEARCH_RAW,",
    "                isResearch = true,",
    '                description = "SAE J1979 PID 9E = engine exhaust flow rate in kg/h (5-byte block). This car CLAIMS it (bit set in the 0180 bitmap `00 24 00 0D`) and ANSWERED it in the 2026-09-16 run with `41 9E 00 20`. The scaling of those bytes is not defined in the tables this project cites, so the raw pair is recorded and NO number is published - an earlier revision of this audit guessed \'32/255 = 25.6 % load\', which is exactly the kind of invented value the no-fake-values rule forbids. Needs one validated frame, or a load sweep against MAF, before it gets a decoder.",',
    "                priority = PollingPriority.SLOW,",
    "                defaultIntervalMs = 3000L",
    "            ),",
]
L = insert_before_entry(L, "01A0", NEW_9A_9E, "new 019A / 019B / 019C / 019E")

save(CAT, L)
print("catalogue edits applied: %d" % edits)
