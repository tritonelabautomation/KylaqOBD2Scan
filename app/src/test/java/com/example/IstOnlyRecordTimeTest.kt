package com.example

import com.example.data.RecordTime
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Source-level enforcement of the owner's mandate, 2026-09-17: *"For all records use IST time only
 * no UTC."*
 *
 * A convention that lives only in a code review comment dies with the next feature branch. This
 * test fails the build if any production source file goes back to formatting a record stamp in UTC
 * - either by asking for the UTC zone explicitly, or by writing a literal `Z` onto a pattern, which
 * is worse: it stamps device-local time and then LABELS it UTC.
 *
 * That second form is not hypothetical. `PidDiscoveryService`, `TripRepository` and `ZipImporter`
 * all did it, so the PID discovery export the owner audits carried `"timestamp":
 * "2026-09-16T08:58:05.839Z"` while the raw log beside it read `[08:57:10.520]` - the same clock
 * wearing two labels, five and a half hours apart from the honest UTC stamps in the session JSON.
 *
 * The single permitted home for a UTC pattern is [RecordTime], which has to READ the legacy
 * `...Z` stamps that every trip file written before 1.0.337 contains. Reading old UTC is
 * compatibility; writing new UTC is the bug.
 */
class IstOnlyRecordTimeTest {

    /** Files allowed to mention a UTC pattern, and why. */
    private val allowList = mapOf(
        "data/RecordTime.kt" to "the single record clock: it must still PARSE legacy ...Z stamps"
    )

    private val banned = listOf(
        Regex("""TimeZone\.getTimeZone\(\s*"UTC"\s*\)""") to
            "explicit UTC zone - records are stamped in IST via RecordTime",
        Regex("""TimeZone\.getTimeZone\(\s*"GMT"\s*\)""") to
            "explicit GMT zone - records are stamped in IST via RecordTime",
        Regex("""SimpleDateFormat\([^)]*'Z'""") to
            "a literal 'Z' claims UTC; RecordTime writes IST with its real offset (+05:30)"
    )

    private fun sourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (dir == null) return null
            val candidate = File(dir, "app/src/main/java/com/example")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun noProductionSourceFormatsARecordStampInUtc() {
        val root = sourceRoot()
        assumeTrue("production sources not visible from ${System.getProperty("user.dir")}", root != null)

        val offenders = mutableListOf<String>()
        root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (allowList.containsKey(relative)) return@forEach
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    val code = line.substringBefore("//")   // prose in comments is not a stamp
                    for ((pattern, why) in banned) {
                        if (pattern.containsMatchIn(code)) {
                            offenders += "$relative:${index + 1}: $why\n      ${line.trim()}"
                        }
                    }
                }
            }

        assertTrue(
            "Records must be stamped in IST, never UTC (owner mandate 2026-09-17). " +
                "Use com.example.data.RecordTime.stamp() / .logStamp() / .display().\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun theAllowListStillPointsAtRealFiles() {
        // A stale allow-list entry would silently stop guarding the file it was written for.
        val root = sourceRoot() ?: return
        for (relative in allowList.keys) {
            assertTrue("allow-list entry no longer exists: $relative", File(root, relative).isFile)
        }
    }

    @Test
    fun recordTimeIsTheOnlyClockAndItSaysItsZone() {
        // Guard the guard: if RecordTime ever stops printing the offset, every stamp in every file
        // becomes ambiguous again.
        val stamp = RecordTime.stamp(1_789_635_425_123L)
        assertTrue("record stamp must print its offset: $stamp", stamp.endsWith("+05:30"))
        assertTrue("record stamp must not claim UTC: $stamp", !stamp.endsWith("Z"))
        assertTrue(RecordTime.zone.id == "Asia/Kolkata")
    }
}
