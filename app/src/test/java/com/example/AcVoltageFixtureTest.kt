package com.example

import com.example.analysis.AcVoltageDetector
import com.example.data.SessionTime
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Regression harness for the owner's LABELLED AC drive (session 2df90142, Kylaq Run
 * 2026-09-15 07:26: AC off for the first ~10 km, switched on during a 90-100 s signal
 * halt) - the real-car oracle for [AcVoltageDetector].
 *
 * The fixture is the trip's own `<id>_samples.csv` (the app's wide samples format with
 * `timestamp_utc`, `RPM` and `voltage_V` columns). It is NOT committed yet: the owner's
 * attachments never reached the agent workspace (upload mount absent, checked three
 * times on 2026-09-16). Drop the file at
 * `app/src/test/resources/2df90142_samples.csv`, or point the AC_FIXTURE_CSV environment
 * variable at it, and this test activates in CI; until then it skips loudly.
 *
 * Assertions are deliberately invariant-level for now (evidence exists, baselines sane,
 * any ON segment starts after trip start). Once the owner confirms the reported first-flip
 * time against their memory of the halt, tighten this to pin the switch window in
 * minutes-from-start - that turns their labelled drive into a permanent tripwire against
 * threshold regressions.
 */
class AcVoltageFixtureTest {

    private fun fixture(): File? {
        val fromEnv = System.getenv("AC_FIXTURE_CSV")
        val candidates = listOfNotNull(
            fromEnv?.let { File(it) },
            File("app/src/test/resources/2df90142_samples.csv"),
            File("src/test/resources/2df90142_samples.csv")
        )
        return candidates.firstOrNull { it.exists() }
    }

    @Test
    fun `owner labelled drive produces sane ac detection`() {
        val file = fixture()
        assumeTrue(
            "AC fixture absent - attach owner 2df90142_samples.csv (resources or AC_FIXTURE_CSV)",
            file != null
        )

        val lines = file!!.readLines().filter { it.isNotBlank() }
        assertTrue("fixture has a header and rows", lines.size > 10)
        val header = lines[0].split(',').map { it.trim() }
        val tsIdx = header.indexOf("timestamp_utc")
        val rpmIdx = header.indexOf("RPM")
        val voltIdx = header.indexOf("voltage_V")
        assertTrue("fixture must carry timestamp_utc/RPM/voltage_V columns", tsIdx >= 0 && voltIdx >= 0)

        fun cell(row: List<String>, idx: Int): Double? =
            if (idx < 0 || idx >= row.size) null else row[idx].trim().toDoubleOrNull()

        val samples = lines.drop(1).mapNotNull { line ->
            val row = line.split(',')
            val ts = SessionTime.parseMillis(row.getOrNull(tsIdx)?.trim()) ?: return@mapNotNull null
            val v = cell(row, voltIdx) ?: return@mapNotNull null
            AcVoltageDetector.Sample(tsMs = ts, voltageV = v, rpm = cell(row, rpmIdx))
        }
        assertTrue("fixture parsed to samples", samples.size > 100)

        val result = AcVoltageDetector.detect(samples)
        assertTrue("a 70-minute drive must yield window evidence", result.hasEvidence)
        assertTrue("quiet baseline ${result.quietMadV} implausible", (result.quietMadV ?: 9.0) in 0.005..0.15)

        val startTs = samples.minOf { it.tsMs }
        result.switchEvents.forEach { (ts, on) ->
            val minutes = (ts - startTs) / 60_000.0
            println("AC fixture: flip to ${if (on) "ON" else "OFF"} at +%.1f min".format(minutes))
            assertTrue("switch inside the trip window", minutes in 0.0..(samples.maxOf { it.tsMs } - startTs) / 60_000.0)
        }
        println(
            "AC fixture: windows=${result.windows} confidence=%.1f acOn=%.1f min quietMad=%.3f onMad=%s"
                .format(
                    result.confidence,
                    result.acOnSeconds / 60.0,
                    result.quietMadV ?: -1.0,
                    result.onMadV?.let { "%.3f".format(it) } ?: "n/a"
                )
        )
        // Owner label: AC off ~10 km (~first third), on after a 90-100 s halt. Once they
        // confirm the printed flip minute, replace the invariant above with a pinned range.
        if (result.switchEvents.any { it.second }) {
            val firstOnMin = (result.switchEvents.first { it.second }.first - startTs) / 60_000.0
            assertTrue("first ON flip ${firstOnMin}min should sit in the middle third of the drive", firstOnMin > 5.0)
        }
    }
}
