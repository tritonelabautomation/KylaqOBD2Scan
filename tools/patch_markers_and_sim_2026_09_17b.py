#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
2026-09-17 pass B, part 2 - the four missing range markers, the simulation and
profile entries for PID A6, and the test counts the marker exclusion changed.

`0160` is the row that produced the export's phantom "Mode 01 PID 60 = 6B 09 00 41":
PID 60 had no catalogue entry, so lookup() fell through to the generic
"Mode 01 PID 60" research row and the 0160 capability bitmap that answered was
printed as if it were a measurement. 0100 / 0120 / 0140 had the same hole; only
0180 and 01A0 were catalogued. All six markers are now explicit, disabled rows.
"""
import io
import re
import sys

CAT = "app/src/main/java/com/example/model/PidDefinition.kt"
BOX = "\u2500"
edits = 0


def find_block(lines, pid_id):
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
    return (s, e, i)


def insert_before_entry(lines, anchor_pid, new_pid, rows, label):
    global edits
    f = find_block(lines, anchor_pid)
    if f is None:
        print("FAIL %s: no anchor entry %s" % (label, anchor_pid))
        sys.exit(1)
    if find_block(lines, new_pid) is not None:
        print("NOOP already present: %s" % label)
        return lines
    edits += 1
    return lines[: f[0]] + rows + lines[f[0]:]


def marker_rows(pid, lo, hi, evidence):
    n = pid[2:]
    return [
        "            // " + BOX * 3 + " 01%s Range marker: PIDs supported [%s-%s] " % (n, lo, hi)
        + BOX * max(3, 24 - len(lo) - len(hi)),
        "            PidDefinition(",
        '                id = "%s", service = "01", pid = "%s",' % (pid, n),
        '                name = "Mode 01 PID %s - range marker (PIDs supported %s-%s)",' % (n, lo, hi),
        '                shortName = "Range %s-%s", unit = "",' % (lo, hi),
        "                dataBytes = 4, decoderType = DecoderType.RESEARCH_RAW,",
        "                isResearch = true,",
        "                enabled = false,",
        '                description = "SAE J1979 PID %s is the 32-bit availability bitmap for PID %s to %s. It is a capability row, never a measurement. %s PidDiscoveryDecoder.decodeSupportedPids excludes it from the supported list and hasNextRange reports it separately.",'
        % (n, lo, hi, evidence),
        "                priority = PollingPriority.SLOW,",
        "                defaultIntervalMs = 3000L",
        "            ),",
    ]


L = io.open(CAT, encoding="utf-8").read().split("\n")

L = insert_before_entry(L, "010C", "0100", marker_rows(
    "0100", "01", "20",
    "The owner run of 2026-09-16 got a garbled stale frame for TX 0100 and the valid 41 00 bitmap arrived one command late, so the whole 0x00 block - rpm, speed, coolant, MAP, throttle, fuel rate, 16 PIDs the app polls on every trip - vanished from a report about the car."),
    "marker 0100")

L = insert_before_entry(L, "0121", "0120", marker_rows(
    "0120", "21", "40",
    "In the owner run of 2026-09-16 the 41 00 bitmap was received during TX 0120 and correctly rejected for base 0x20, which is why the 0x20 block is UNKNOWN rather than empty - it was never decoded at all."),
    "marker 0120")

L = insert_before_entry(L, "0141", "0140", marker_rows(
    "0140", "41", "60",
    "The owner run answered `41 40 FE D0 84 01` = PID 41 42 43 44 45 46 47 49 4A 4C 51 56."),
    "marker 0140")

L = insert_before_entry(L, "0161", "0160", marker_rows(
    "0160", "61", "80",
    "This is the hole that produced the phantom export row 'Mode 01 PID 60 = 6B 09 00 41': PID 60 had no catalogue entry, lookup() fell through to the generic research row, and the 0160 capability bitmap that answered was printed as if it were a measurement. The owner run answered `41 60 6B 09 00 41` = PID 62 63 65 67 68 6D 70 7A."),
    "marker 0160")

io.open(CAT, "w", encoding="utf-8").write("\n".join(L))
print("catalogue markers added: %d" % edits)

# ---------------------------------------------------------------------------
# SimulationTransport: PID A6 is the odometer, not random research bytes
# ---------------------------------------------------------------------------
SIM = "app/src/main/java/com/example/bluetooth/SimulationTransport.kt"
s = io.open(SIM, encoding="utf-8").read()
old = '''            // Research PID 01A6 - Unknown EA211 Channel
            cleanCmd == "01A6" -> {
                val b0 = Random.nextInt(0, 255)
                val b1 = Random.nextInt(0, 255)
                val b2 = 0x1A
                lines.add("7E8 05 41 A6 %02X %02X %02X".format(b0, b1, b2))
            }'''
new = '''            // J1979 PID A6 - ODOMETER, ((A*2^24)+(B*2^16)+(C*2^8)+D)/10 km. This used to be
            // three RANDOM bytes labelled "Unknown EA211 Channel", which is exactly what a
            // simulation must not do: it teaches the UI to trust a channel that has no
            // meaning. The real car answered `41 A6 00 00 86 EB` = 10279.5 km on 2026-09-16.
            cleanCmd == "01A6" -> {
                val km = 10279.5 + (simTimeStep * 30.0 / 3600.0) // ~30 km/h average
                val raw = (km * 10.0).toLong().coerceIn(0L, 99_999_999L)
                val b0 = ((raw shr 24) and 0xFF).toInt()
                val b1 = ((raw shr 16) and 0xFF).toInt()
                val b2 = ((raw shr 8) and 0xFF).toInt()
                val b3 = (raw and 0xFF).toInt()
                lines.add("7E8 06 41 A6 %02X %02X %02X %02X".format(b0, b1, b2, b3))
            }'''
if new in s:
    print("NOOP already applied: SimulationTransport 01A6")
elif s.count(old) != 1:
    print("FAIL SimulationTransport 01A6 anchor count %d" % s.count(old))
    sys.exit(1)
else:
    s = s.replace(old, new, 1)
    io.open(SIM, "w", encoding="utf-8").write(s)
    print("SimulationTransport 01A6 -> odometer")

# ---------------------------------------------------------------------------
# ProfileDefinitions: the VW experimental profile asked for A6 as raw research
# ---------------------------------------------------------------------------
PRO = "app/src/main/java/com/example/model/ProfileDefinitions.kt"
s = io.open(PRO, encoding="utf-8").read()
old = '''        DiagnosticRequest("01A6", "VW Candidate A6", "01", "A6", "Experimental EA211 value", DecoderType.RESEARCH_RAW),'''
new = '''        // Was "VW Candidate A6 / Experimental EA211 value". J1979 PID A6 is the ODOMETER and
        // the real Kylaq answered it on 2026-09-16 with 00 00 86 EB = 10279.5 km, so it is a
        // known channel, not a VW research candidate.
        DiagnosticRequest("01A6", "Odometer", "01", "A6", "J1979 odometer, 0.1 km/bit (real car answered 10279.5 km)", DecoderType.ODOMETER_4B),'''
if new in s:
    print("NOOP already applied: ProfileDefinitions 01A6")
elif s.count(old) != 1:
    print("FAIL ProfileDefinitions 01A6 anchor count %d" % s.count(old))
    sys.exit(1)
else:
    s = s.replace(old, new, 1)
    io.open(PRO, "w", encoding="utf-8").write(s)
    print("ProfileDefinitions 01A6 -> odometer")

# ---------------------------------------------------------------------------
# PidDiscoveryDecoderTest: the marker is no longer emitted as a supported PID
# ---------------------------------------------------------------------------
T = "app/src/test/java/com/example/PidDiscoveryDecoderTest.kt"
s = io.open(T, encoding="utf-8").read()
pairs = [
    ("        assertEquals(18, result.supportedPids.size)",
     "        // 0x20 is bit 32 of this bitmap: the \"next block\" marker, not a data PID.\n"
     "        assertEquals(17, result.supportedPids.size)\n"
     "        assertFalse(result.supportedPids.contains(0x20))"),
    ("        assertEquals(32, result.allTestedPids.size)",
     "        // 31 PIDs are tested per block (0x01-0x1F plus the marker excluded = 31 slots)\n"
     "        assertEquals(31, result.allTestedPids.size)"),
]
for old, new in pairs:
    if new in s:
        print("NOOP already applied: %s" % old.strip()[:40])
        continue
    if s.count(old) != 1:
        print("FAIL %s anchor count %d" % (old.strip()[:40], s.count(old)))
        sys.exit(1)
    s = s.replace(old, new, 1)
    edits += 1
if "import org.junit.Assert.assertFalse" not in s:
    s = s.replace("import org.junit.Assert.assertEquals",
                  "import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse", 1)
io.open(T, "w", encoding="utf-8").write(s)
print("PidDiscoveryDecoderTest counts fixed")
