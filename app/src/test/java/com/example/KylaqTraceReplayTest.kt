package com.example

import com.example.model.DefaultPidDefinitions
import com.example.model.LiveTelemetryValue
import com.example.model.PidDefinition
import com.example.model.ValueSource
import com.example.protocol.IsoTpParser
import com.example.protocol.PidDecoder
import com.example.scheduler.LiveTelemetryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the bundled Škoda Kylaq reference trace
 * (`app/src/test/resources/traces/kylaq/f39f1ebd_raw.txt`, captured 2026-09-05) through the
 * real parse/decode/store pipeline and asserts the numbers the dashboard must show.
 *
 * The trace is the ground truth for this vehicle's behaviour:
 *
 *  * two ECUs answer every request — `7E8` (engine) and `7E9` — with slightly different
 *    values, e.g. `7E804410C0F28` (970 rpm) next to `7E904410C0F34` (973 rpm);
 *  * the session ends with a transport failure (`NO DATA` for every PID), which must be
 *    reported as "no data" and never as "PID not supported";
 *  * unsolicited coolant frames arrive inside a `010C` read window and must not be decoded
 *    as engine speed.
 *
 * Pure JVM: no Robolectric, no Android runtime.
 */
class KylaqTraceReplayTest {

    private companion object {
        const val TRACE_RESOURCE = "traces/kylaq/f39f1ebd_raw.txt"
        const val VIN_REQUEST = "0902"
        /** Engine ECU — the preferred source for every powertrain PID on this vehicle. */
        const val ENGINE = "7E8"
        const val SECONDARY = "7E9"

        /** The PIDs the live dashboard renders, in the order the trace polls them. */
        val UI_PIDS = listOf("010C", "010D", "0111", "010B", "0105", "010F", "0142")

        val TRACE_LINE = Regex("""^(\d{2}:\d{2}:\d{2}\.\d{3})\s+(TX|RX)\s+([><])\s+(.*)$""")
    }

    /** One ELM327 transaction: the transmitted command plus every line received for it. */
    private class Txn(val cmd: String, val rx: MutableList<String> = mutableListOf())

    private class Replay(
        val store: LiveTelemetryStore,
        val pidQueries: Int,
        val decodedSamples: Int,
        val noDataResponses: Int,
        val lateFrames: Int,
        val vinLines: List<String>
    )

    // ------------------------------------------------------------------ fixture

    private fun traceLines(): List<String> {
        val stream = javaClass.classLoader?.getResourceAsStream(TRACE_RESOURCE)
        assertNotNull("test resource $TRACE_RESOURCE must be on the unit-test classpath", stream)
        return stream!!.bufferedReader().use { it.readLines() }
    }

    /**
     * Groups RX lines under the preceding TX command, exactly as the transport does.
     * Fixture annotations (`--- ... ---`) do not match [TRACE_LINE] and are skipped.
     */
    private fun transactions(): List<Txn> {
        val out = mutableListOf<Txn>()
        var current: Txn? = null
        for (raw in traceLines()) {
            val match = TRACE_LINE.matchEntire(raw.trim()) ?: continue
            val direction = match.groupValues[2]
            val payload = match.groupValues[4].trim()
            if (direction == "TX") {
                current = Txn(payload).also { out += it }
            } else {
                current?.rx?.add(payload)
            }
        }
        return out
    }

    // ------------------------------------------------------------------- replay

    private fun unavailable(def: PidDefinition) = LiveTelemetryValue(
        parameterName = def.name,
        numericValue = null,
        displayValue = "Not available",
        unit = def.unit,
        source = ValueSource.STANDARD_OBD,
        timestampMonotonic = 1_000L,
        isValid = false,
        isStale = false,
        sourcePid = def.id,
        sourceEcuId = ENGINE,
        rawBytes = null
    )

    private fun replay(): Replay {
        val defs = DefaultPidDefinitions.getDefaults().associateBy { it.id }
        val store = LiveTelemetryStore()
        var pidQueries = 0
        var decodedSamples = 0
        var noData = 0
        var lateFrames = 0
        val vinLines = mutableListOf<String>()

        for (txn in transactions()) {
            val cmd = txn.cmd.uppercase()
            if (cmd == VIN_REQUEST) vinLines += txn.rx

            val def = defs[cmd] ?: continue // AT commands, VIN reads, session noise
            pidQueries++

            // Rule 6 of the protocol contract: NO DATA is a transport failure, never
            // "PID not supported". It is attributed to the ECU that was addressed.
            if (txn.rx.any { it.uppercase().startsWith("NO DATA") }) {
                noData++
                store.publishUnavailable(
                    pidId = def.id,
                    ecuId = ENGINE,
                    item = unavailable(def),
                    preferredEcu = ENGINE
                )
                continue
            }

            val messages = IsoTpParser.reassembleLines(txn.rx)
                .sortedBy { if (it.canId?.uppercase() == ENGINE) 0 else 1 }

            for (msg in messages) {
                if (msg.isMalformed) continue
                if (def.isLateFrameForOtherPid(msg.reconstructedBytes)) {
                    lateFrames++
                    continue
                }
                val decoded = PidDecoder.decode(def, msg.reconstructedBytes)
                store.publish(
                    pidId = def.id,
                    ecuId = msg.canId,
                    item = LiveTelemetryValue(
                        parameterName = def.name,
                        numericValue = decoded.numericValue,
                        displayValue = decoded.displayValue,
                        unit = decoded.unit,
                        source = ValueSource.STANDARD_OBD,
                        timestampMonotonic = 1_000L,
                        isValid = decoded.isKnown,
                        isStale = false,
                        sourcePid = def.id,
                        sourceEcuId = msg.canId,
                        rawBytes = msg.reconstructedBytes
                    ),
                    preferredEcu = ENGINE
                )
                decodedSamples++
            }
        }

        return Replay(store, pidQueries, decodedSamples, noData, lateFrames, vinLines)
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun testEveryDashboardPidIsPublishedUnderItsPlainKey() {
        val store = replay().store

        for (pid in UI_PIDS) {
            assertNotNull("plain PID key $pid must be published for the UI", store.decodedMap.value[pid])
        }
        assertEquals("exactly one plain key per PID — no ECU-suffixed keys",
            UI_PIDS.size, store.activePidCount)
        assertEquals(UI_PIDS.toSet(), store.decodedMap.value.keys)
    }

    @Test
    fun testTraceReplayProducesTheReferenceValues() {
        val store = replay().store

        // 010C — last live frame is 7E9 04 41 0C 0F 46 -> (0x0F*256 + 0x46) / 4 = 977.5 rpm.
        // 7E8 answered 0x0F4A (978.5) but then stopped answering (NO DATA), so the healthy
        // secondary ECU carries the value instead of the dashboard going blank.
        assertEquals(977.5, store.numericValue("010C", ENGINE)!!, 0.001)
        assertTrue(store.decodedMap.value["010C"]!!.startsWith("97"))

        // 010D — 7E8 03 41 0D 00 -> 0 km/h (vehicle stationary throughout the capture)
        assertEquals(0.0, store.numericValue("010D", ENGINE)!!, 0.001)

        // 0111 — 41 11 25 -> 0x25 * 100 / 255 = 14.5 %
        assertEquals(14.5, store.numericValue("0111", ENGINE)!!, 0.05)

        // 010B — 7E8 03 41 0B 23 -> 35 kPa (7E8 is the only ECU answering MAP)
        assertEquals(35.0, store.numericValue("010B", ENGINE)!!, 0.001)

        // 0105 — 41 05 6E -> 110 - 40 = 70 °C
        assertEquals(70.0, store.numericValue("0105", ENGINE)!!, 0.001)

        // 0142 — 7E8 04 41 42 33 68 -> (0x33*256 + 0x68) / 1000 = 13.16 V
        assertEquals(13.16, store.numericValue("0142", ENGINE)!!, 0.001)
        assertEquals("the engine ECU is preferred over 7E9's 13.33 V reading",
            13.16, store.telemetryMap.value["${ENGINE}_0142"]!!.numericValue!!, 0.001)
        assertEquals(13.332, store.telemetryMap.value["${SECONDARY}_0142"]!!.numericValue!!, 0.001)

        // 010F — answered by 7E8 only, and its final transaction was NO DATA.
        assertNull(store.numericValue("010F", ENGINE))
        assertEquals("Not available", store.decodedMap.value["010F"])
    }

    @Test
    fun testBothEcusAreRetainedSeparatelyForMultiEcuPids() {
        val store = replay().store

        assertEquals(listOf(ENGINE, SECONDARY), store.ecusFor("010C"))
        assertEquals(listOf(ENGINE, SECONDARY), store.ecusFor("010D"))
        assertEquals(listOf(ENGINE, SECONDARY), store.ecusFor("0111"))
        assertEquals(listOf(ENGINE, SECONDARY), store.ecusFor("0105"))
        assertEquals("MAP is answered by the engine ECU alone in this trace",
            listOf(ENGINE), store.ecusFor("010B"))

        assertNotNull(store.telemetryMap.value["${ENGINE}_010C"])
        assertNotNull(store.telemetryMap.value["${SECONDARY}_010C"])
    }

    @Test
    fun testLateFramesForAnotherPidAreNeverPublished() {
        val replay = replay()

        // The fixture's "ENGINE WARMING UP" coolant frames (7E90341056F / 7E80341056F)
        // land inside a 010C read window.
        assertEquals("both unsolicited coolant frames must be recognised and skipped",
            2, replay.lateFrames)

        // Before the fix they were decoded as RPM -> INVALID_RESPONSE, overwriting the good
        // 7E9 engine-speed sample and blanking the dashboard.
        assertNotNull(replay.store.numericValue("010C", ENGINE))

        // ... and they must not be mistaken for a coolant reading either (still 70 °C, not 71).
        assertEquals(70.0, replay.store.numericValue("0105", ENGINE)!!, 0.001)
    }

    @Test
    fun testNoDataAtSessionEndIsCountedButNotTreatedAsUnsupported() {
        val replay = replay()

        assertEquals("the trace ends with NO DATA for 010F, 010C, 010D and 0111",
            4, replay.noDataResponses)
        assertTrue("the fixture polls the seven dashboard PIDs many times", replay.pidQueries >= 33)
        assertTrue("most polls return two decodable frames (7E8 + 7E9)", replay.decodedSamples >= 50)

        // PIDs that never failed keep their last good value.
        assertEquals(35.0, replay.store.numericValue("010B", ENGINE)!!, 0.001)
        assertEquals(70.0, replay.store.numericValue("0105", ENGINE)!!, 0.001)
    }

    @Test
    fun testVinFramesInTheTraceReassembleToTheKylaqVin() {
        val vinLines = replay().vinLines
        assertTrue("the trace contains two 0902 VIN reads", vinLines.size >= 3)

        val messages = IsoTpParser.reassembleLines(vinLines.take(3))
        assertEquals(1, messages.size)

        val msg = messages.first()
        assertEquals(ENGINE, msg.canId)
        assertTrue("VIN message must be complete", msg.isComplete)
        assertEquals("10 14 = 20 bytes total", 20, msg.totalExpectedLength)
        assertEquals("first-frame payload must not be duplicated", 20, msg.reconstructedBytes.size)

        // 49 02 01 + 17 ASCII characters
        val vin = msg.reconstructedBytes.drop(3).joinToString("") { (it and 0xFF).toChar().toString() }
        assertEquals("MEXKPEPC2TG028855", vin)
        assertEquals("MEX", vin.substring(0, 3))
    }
}
