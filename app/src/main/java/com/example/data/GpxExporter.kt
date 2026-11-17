package com.example.data

import java.io.File
import java.io.StringWriter
import java.util.Locale

/**
 * High-precision GPS route point for map rendering, trip replay and GPX/KML export.
 */
data class GpsRoutePoint(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double? = null,
    val speedKmh: Double = 0.0,
    val rpm: Double? = null,
    val isHalt: Boolean = false
)

object GpxExporter {

    /**
     * Generates a standard GPX 1.1 XML string from route points.
     * Compatible with Strava, Google Earth, Garmin Connect, and GPX viewers.
     */
    /**
     * Streams a GPX document straight to [writer], one track point at a time.
     *
     * AUDIT MEM-01 (2026-11-12): the original built the entire document in a `StringWriter`
     * (`points.joinToString`, then `writer.toString()`) and only then wrote it out - two full
     * copies of the whole route in memory at once. On a long drive that is the same class of
     * allocation that caused the 2026-11-16 OOM, in an export path the heap fix did not cover.
     * Writing incrementally keeps peak memory at a couple of formatted lines regardless of
     * route length, and produces byte-identical output.
     */
    fun writeGpx(
        writer: Appendable,
        tripName: String,
        points: List<GpsRoutePoint>,
        vehicle: String = "Škoda Kylaq 1.0 TSI"
    ) {
        writer.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        writer.appendLine("<gpx version=\"1.1\" creator=\"KylaqOBD2Scan\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        writer.appendLine("  <metadata>")
        writer.appendLine("    <name><![CDATA[$tripName]]></name>")
        writer.appendLine("    <desc><![CDATA[Recorded with $vehicle]]></desc>")
        if (points.isNotEmpty()) {
            writer.appendLine("    <time>${RecordTime.format("yyyy-MM-dd'T'HH:mm:ssXXX", points.first().timestampMs)}</time>")
        }
        writer.appendLine("  </metadata>")
        writer.appendLine("  <trk>")
        writer.appendLine("    <name><![CDATA[$tripName]]></name>")
        writer.appendLine("    <trkseg>")

        for (pt in points) {
            writer.append("      <trkpt lat=\"${String.format(Locale.US, "%.6f", pt.latitude)}\" lon=\"${String.format(Locale.US, "%.6f", pt.longitude)}\">")
            pt.altitudeM?.let { ele ->
                writer.append("<ele>${String.format(Locale.US, "%.1f", ele)}</ele>")
            }
            writer.append("<time>${RecordTime.format("yyyy-MM-dd'T'HH:mm:ssXXX", pt.timestampMs)}</time>")
            writer.append("<extensions><speed>${String.format(Locale.US, "%.2f", pt.speedKmh / 3.6)}</speed>")
            pt.rpm?.let { rpm -> writer.append("<rpm>${rpm.toInt()}</rpm>") }
            writer.append("</extensions>")
            writer.appendLine("</trkpt>")
        }

        writer.appendLine("    </trkseg>")
        writer.appendLine("  </trk>")
        writer.appendLine("</gpx>")
    }

    /**
     * Streams a KML 2.2 document straight to [writer], one coordinate at a time.
     * See [writeGpx] for why this exists (AUDIT MEM-01).
     */
    fun writeKml(
        writer: Appendable,
        tripName: String,
        points: List<GpsRoutePoint>
    ) {
        writer.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        writer.appendLine("<kml xmlns=\"http://www.opengis.net/kml/2.2\">")
        writer.appendLine("  <Document>")
        writer.appendLine("    <name><![CDATA[$tripName]]></name>")
        writer.appendLine("    <Style id=\"routeLine\">")
        writer.appendLine("      <LineStyle>")
        writer.appendLine("        <color>ff00e5ff</color>") // Cyan color in AABBGGRR hex
        writer.appendLine("        <width>5</width>")
        writer.appendLine("      </LineStyle>")
        writer.appendLine("    </Style>")
        writer.appendLine("    <Placemark>")
        writer.appendLine("      <name><![CDATA[$tripName Route]]></name>")
        writer.appendLine("      <styleUrl>#routeLine</styleUrl>")
        writer.appendLine("      <LineString>")
        writer.appendLine("        <extrude>1</extrude>")
        writer.appendLine("        <tessellate>1</tessellate>")
        writer.appendLine("        <coordinates>")

        // Coordinates are space separated in KML; emit them incrementally instead of
        // materialising one giant joined string. The 10-space indent matches the original
        // `writer.appendLine("          $coordString")` byte for byte.
        writer.append("          ")
        var first = true
        for (pt in points) {
            if (!first) writer.append(" ")
            first = false
            writer.append(
                String.format(
                    Locale.US,
                    "%.6f,%.6f,%.1f",
                    pt.longitude,
                    pt.latitude,
                    pt.altitudeM ?: 0.0
                )
            )
        }
        writer.appendLine()

        writer.appendLine("        </coordinates>")
        writer.appendLine("      </LineString>")
        writer.appendLine("    </Placemark>")
        writer.appendLine("  </Document>")
        writer.appendLine("</kml>")
    }

    fun exportToGpx(
        tripName: String,
        points: List<GpsRoutePoint>,
        vehicle: String = "Škoda Kylaq 1.0 TSI"
    ): String {
        val writer = StringWriter()
        writeGpx(writer, tripName, points, vehicle)
        return writer.toString()
    }

    /**
     * Generates a standard KML 2.2 document string for Google Earth / Google Maps.
     */
    fun exportToKml(
        tripName: String,
        points: List<GpsRoutePoint>
    ): String {
        val writer = StringWriter()
        writeKml(writer, tripName, points)
        return writer.toString()
    }

    /**
     * Saves GPX content to the designated export file.
     */
    fun saveGpxFile(destFile: File, tripName: String, points: List<GpsRoutePoint>): File {
        destFile.parentFile?.mkdirs()
        // AUDIT MEM-01: stream to disk; never build the document in memory first.
        destFile.bufferedWriter().use { writeGpx(it, tripName, points) }
        return destFile
    }

    /**
     * Saves KML content to the designated export file.
     */
    fun saveKmlFile(destFile: File, tripName: String, points: List<GpsRoutePoint>): File {
        destFile.parentFile?.mkdirs()
        // AUDIT MEM-01: stream to disk; never build the document in memory first.
        destFile.bufferedWriter().use { writeKml(it, tripName, points) }
        return destFile
    }

    const val ROUTE_HEADER = "timestampMs,latitude,longitude,altitudeM,speedKmh,rpm,isHalt"

    fun routePointToCsvRow(pt: GpsRoutePoint): String =
        "${pt.timestampMs},${String.format(Locale.US, "%.6f", pt.latitude)},${String.format(Locale.US, "%.6f", pt.longitude)},${pt.altitudeM?.let { String.format(Locale.US, "%.1f", it) } ?: ""},${String.format(Locale.US, "%.1f", pt.speedKmh)},${pt.rpm?.toInt() ?: ""},${if (pt.isHalt) 1 else 0}"

    fun saveRoutePointsToCsv(destFile: File, points: List<GpsRoutePoint>): File {
        destFile.parentFile?.mkdirs()
        destFile.printWriter().use { out ->
            out.println(ROUTE_HEADER)
            for (p in points) {
                out.println(routePointToCsvRow(p))
            }
        }
        return destFile
    }

    fun readRoutePointsFromCsv(file: File): List<GpsRoutePoint> {
        val out = mutableListOf<GpsRoutePoint>()
        if (!file.exists()) return out
        runCatching {
            file.useLines { lines ->
                var isHeader = true
                for (line in lines) {
                    if (isHeader) {
                        isHeader = false
                        continue
                    }
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val parts = trimmed.split(',')
                    if (parts.size >= 3) {
                        val ts = parts[0].toLongOrNull() ?: 0L
                        val lat = parts[1].toDoubleOrNull() ?: continue
                        val lon = parts[2].toDoubleOrNull() ?: continue
                        val alt = parts.getOrNull(3)?.toDoubleOrNull()
                        val spd = parts.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                        val rpm = parts.getOrNull(5)?.toDoubleOrNull()
                        val halt = parts.getOrNull(6) == "1"
                        out.add(GpsRoutePoint(ts, lat, lon, alt, spd, rpm, halt))
                    }
                }
            }
        }
        return out
    }
}
