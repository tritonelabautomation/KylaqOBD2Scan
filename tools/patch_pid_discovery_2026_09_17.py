import io, sys

def patch(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        n = s.count(old)
        if n != 1:
            print('FAIL anchor count=%d in %s :: %r' % (n, path, old[:70]))
            sys.exit(1)
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8').write(s)
    print('OK %s (%d anchors)' % (path, len(pairs)))

NL = '\n'

def j(*lines):
    return NL.join(lines)

# ---------------------------------------------------------------- 1. transport
T = 'app/src/main/java/com/example/bluetooth/Elm327Transport.kt'
old1 = j(
'        try {',
'            // Write command with carriage return',
'            val cmdBytes = (cleanCmd + "\\r").toByteArray(Charsets.US_ASCII)')
new1 = j(
'        try {',
'            // ELM327 BUFFER-LAG ROOT CAUSE (owner Kylaq discovery run 2026-09-16 08:57 IST,',
'            // re-confirmed by the exported JSON): the adapter can still be holding the PREVIOUS',
'            // command\'s frame when the next one is written, so answers land one command late -',
'            // TX 0100 returned a stale garbled frame, TX 0120 returned the 41 00 bitmap, TX 0180',
'            // returned the 41 A0 bitmap. Rejecting the PID mismatch was correct, but the cost was',
'            // real: the 0x00/0x20 blocks vanished (RPM, speed, coolant, MAP, timing, fuel rate),',
'            // PID 83/85/86 were never discovered, and a capability bitmap was decoded as "DPF',
'            // Temperature". Anything already sitting in the socket when we are about to transmit',
'            // is by definition an orphan - drop it so the next read belongs to this command.',
'            // Non-blocking, bounded, never waits for a response.',
'            var drainPasses = 0',
'            while (drainPasses < 8) {',
'                val pending = inStream.available()',
'                if (pending <= 0) break',
'                val skipped = inStream.read(ByteArray(minOf(pending, 512)))',
'                if (skipped <= 0) break',
'                drainPasses++',
'            }',
'            if (drainPasses > 0) {',
'                logRaw(isTx = false, canId = null, text = "[DRAINED $drainPasses stale RX chunk(s) before TX]", status = "LAG_GUARD")',
'            }',
'',
'            // Write command with carriage return',
'            val cmdBytes = (cleanCmd + "\\r").toByteArray(Charsets.US_ASCII)')
patch(T, [(old1, new1)])

# ---------------------------------------------------------------- 2. discovery decoder
D = 'app/src/main/java/com/example/protocol/PidDiscoveryDecoder.kt'
old2a = j(
'        if (bitmap.size < 4) return emptyList()',
'        val supported = mutableListOf<Int>()',
'',
'        for (i in 0 until 32) {',
'            val pidNum = basePid + (i + 1)')
new2a = j(
'        if (bitmap.size < 4) return emptyList()',
'        val supported = mutableListOf<Int>()',
'',
'        // Bit 32 of a block (basePid + 0x20) is the "next 32 PIDs are supported" MARKER, not a',
'        // parameter - hasNextRange() reports it separately. Emitting it as a supported PID made',
'        // the owner\'s 2026-09-16 export list PID 60 and PID 80 as data channels, TX them during',
'        // validation, and decode the capability bitmap that answered as if it were a measurement',
'        // ("Mode 01 PID 60 = 6B 09 00 41", "DPF Temperature = 00 24 00 0D" on a petrol car). It',
'        // also burned the real 0180 answer, so PID 83/85/86 were never discovered at all.',
'        val continuationPid = if (basePid < 0xE0) basePid + 0x20 else -1',
'',
'        for (i in 0 until 32) {',
'            val pidNum = basePid + (i + 1)',
'            if (pidNum == continuationPid) continue')
old2b = j(
'    fun allTestedPidsForRange(basePid: Int): List<Int> {',
'        return (1..32).mapNotNull { offset ->',
'            val pidNum = basePid + offset',
'            if (pidNum <= 0xFF) pidNum else null',
'        }',
'    }')
new2b = j(
'    fun allTestedPidsForRange(basePid: Int): List<Int> {',
'        val continuationPid = if (basePid < 0xE0) basePid + 0x20 else -1',
'        return (1..32).mapNotNull { offset ->',
'            val pidNum = basePid + offset',
'            if (pidNum <= 0xFF && pidNum != continuationPid) pidNum else null',
'        }',
'    }')
patch(D, [(old2a, new2a), (old2b, new2b)])
print('part 1 done')
