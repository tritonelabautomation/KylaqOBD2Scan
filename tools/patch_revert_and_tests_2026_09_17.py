import io, sys

def patch(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        if old == new:
            print('NOOP :: ' + repr(old[:60])); sys.exit(1)
        n = s.count(old)
        if n != 1:
            print('FAIL count=' + str(n) + ' :: ' + repr(old[:70])); sys.exit(1)
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8').write(s)
    print('OK ' + path + ' (' + str(len(pairs)) + ' anchors)')

P = 'app/src/main/java/com/example/model/PidDefinition.kt'

# 0167 - put the real-car sentinel behaviour back, keep the honest documentation.
OLD_67 = """                decoderType = DecoderType.CATALYST_TEMP,
                dataBytes = 2,
                formulaDisplay = "((A * 256) + B) / 10 - 40",
                description = "SAE J1979 PID 67: engine coolant temperature 2, 2 bytes in 0.1 degC steps with a -40 offset. Catalogued as 1 byte A - 40, which is why the owner run 2026-09-16 could only report implausible raw no data.","""

NEW_67 = """                decoderType = DecoderType.TEMP_MINUS_40,
                formulaDisplay = "A - 40",
                // DELIBERATELY NOT the J1979 2-byte form. SAE says PID 67 is 2 bytes in
                // 0.1 degC steps with a -40 offset, but the real car (owner run 2026-09-16,
                // frame 41 67 03 50 43) answers with FIVE payload bytes - neither form fits.
                // Under A - 40 the ECU sentinel 0x03 decodes to -37 degC and the plausibility
                // gate rejects it as "implausible raw - no data", which is the honest outcome
                // and the one pinned by coolant2_4167035043_sentinelRejectedAsNoData. Under
                // the 2-byte form the same frame yields (3*256+80)/10-40 = 44.4 degC: a
                // plausible-looking number invented out of a sentinel. NO-FAKE-VALUES wins
                // over the spec sheet until the payload is validated byte by byte.
                description = "Radiator outlet or secondary coolant temperature. On this car the ECU returns a no-sensor sentinel that the plausibility gate rejects - see the comment above.",
                dataBytes = 1,"""

OLD_68 = """                dataBytes = 2, decoderType = DecoderType.CATALYST_TEMP,
                formulaDisplay = "((A * 256) + B) / 10 - 40",
                description = "SAE J1979 PID 68: intake air temperature 2, 2 bytes in 0.1 degC steps with a -40 offset (was 1 byte A - 40, hence the implausible-raw verdict in the owner run 2026-09-16).","""

NEW_68 = """                dataBytes = 1, decoderType = DecoderType.TEMP_MINUS_40,
                // Same ruling as PID 67: the J1979 2-byte form was tried on paper and
                // reverted, because the only real evidence we hold (owner run 2026-09-16) is
                // a sentinel rejection, not a validated temperature. Changing the formula
                // without a car sample risks turning a rejected sentinel into a believable
                // number. Re-test with a logged frame before touching this again.
                description = "Secondary intake air temperature (post-throttle body, EA211). The owner run 2026-09-16 returned an implausible raw payload that the honesty gate rejected; kept as 1-byte A - 40 until a real frame is validated.",
                formulaDisplay = "A - 40","""

patch(P, [(OLD_67, NEW_67), (OLD_68, NEW_68)])

# ---------------------------------------------------------------- test updates
T1 = 'app/src/test/java/com/example/KylaqDiscoveryComprehensiveTest.kt'
pairs1 = [
("""        // 13 (00010011) -> 1C, 1F, 20
        val bitmap = byteArrayOf(0xBE.toByte(), 0x3E.toByte(), 0xB8.toByte(), 0x13.toByte())
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x00, bitmap)

        assertEquals(18, supported.size)
        assertTrue(supported.contains(0x0C)) // Engine RPM
        assertTrue(supported.contains(0x0D)) // Vehicle Speed
        assertTrue(supported.contains(0x20)) // Next range indicator
        assertTrue(PidDiscoveryDecoder.hasNextRange(bitmap))""",
 """        // 13 (00010011) -> 1C, 1F, and bit 32 = the 0120 range MARKER
        val bitmap = byteArrayOf(0xBE.toByte(), 0x3E.toByte(), 0xB8.toByte(), 0x13.toByte())
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x00, bitmap)

        // 2026-09-17: the range marker (basePid + 0x20) is NOT a data PID and must never be
        // emitted as one - it is what made the owner export "validate" PID 60 and PID 80.
        assertEquals(17, supported.size)
        assertTrue(supported.contains(0x0C)) // Engine RPM
        assertTrue(supported.contains(0x0D)) // Vehicle Speed
        assertFalse(supported.contains(0x20)) // range marker, reported by hasNextRange only
        assertTrue(PidDiscoveryDecoder.hasNextRange(bitmap))"""),
("""        // 80 00 00 01 -> PID 0x21 and PID 0x40 supported
        val bitmap = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x20, bitmap)

        assertEquals(listOf(0x21, 0x40), supported)""",
 """        // 80 00 00 01 -> PID 0x21 supported, plus bit 32 = the 0140 range marker
        val bitmap = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x20, bitmap)

        assertEquals(listOf(0x21), supported)"""),
("""        // FED00001 -> 41..45, 49, 4B, 60
        val bitmap = byteArrayOf(0xFE.toByte(), 0xD0.toByte(), 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x40, bitmap)

        assertTrue(supported.contains(0x41))
        assertTrue(supported.contains(0x42)) // Control module voltage
        assertTrue(supported.contains(0x60))""",
 """        // FED00001 -> 41..45, 49, 4B (0x60 is the range marker, never a data PID)
        val bitmap = byteArrayOf(0xFE.toByte(), 0xD0.toByte(), 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x40, bitmap)

        assertTrue(supported.contains(0x41))
        assertTrue(supported.contains(0x42)) // Control module voltage
        assertFalse(supported.contains(0x60))"""),
("""        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x60, bitmap)

        assertEquals(listOf(0x70, 0x80), supported)""",
 """        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x60, bitmap)

        assertEquals(listOf(0x70), supported)"""),
("""        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x80, bitmap)

        assertEquals(listOf(0x81, 0xA0), supported)""",
 """        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x80, bitmap)

        assertEquals(listOf(0x81), supported)"""),
("""        val supported = PidDiscoveryDecoder.decodeSupportedPids(0xA0, bitmap)

        assertEquals(listOf(0xA9, 0xC0), supported)""",
 """        val supported = PidDiscoveryDecoder.decodeSupportedPids(0xA0, bitmap)

        assertEquals(listOf(0xA9), supported)"""),
("""        val supported = PidDiscoveryDecoder.decodeSupportedPids(0xC0, bitmap)

        assertEquals(listOf(0xC2, 0xE0), supported)""",
 """        val supported = PidDiscoveryDecoder.decodeSupportedPids(0xC0, bitmap)

        assertEquals(listOf(0xC2), supported)"""),
]
patch(T1, pairs1)

T2 = 'app/src/test/java/com/example/PidDiscoveryDecoderTest.kt'
pairs2 = [
("""        val expectedPids = listOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x11, 0x13, 0x14, 0x15,
            0x1C, 0x1F, 0x20
        )""",
 """        // 2026-09-17: 0x20 here is the range marker, not a parameter - excluded.
        val expectedPids = listOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x11, 0x13, 0x14, 0x15,
            0x1C, 0x1F
        )"""),
("""        val supported = PidDiscoveryDecoder.decodeSupportedPids(basePid = 0x00, bitmap = bitmap)
        assertEquals(32, supported.size)
        assertEquals(1, supported.first())
        assertEquals(32, supported.last())""",
 """        val supported = PidDiscoveryDecoder.decodeSupportedPids(basePid = 0x00, bitmap = bitmap)
        // 31 data PIDs: 0x01-0x1F. 0x20 is the range marker and is never emitted.
        assertEquals(31, supported.size)
        assertEquals(1, supported.first())
        assertEquals(0x1F, supported.last())"""),
("""        // Only first and last bit set: 0x21 and 0x40
        val bitmap = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(basePid = 0x20, bitmap = bitmap)
        assertEquals(listOf(0x21, 0x40), supported)""",
 """        // First bit set (0x21) plus bit 32, which is the 0x40 range marker
        val bitmap = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(basePid = 0x20, bitmap = bitmap)
        assertEquals(listOf(0x21), supported)"""),
]
patch(T2, pairs2)
print('part 4 done')
