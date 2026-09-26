package com.example.data

import java.io.File
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
    fun exportToGpx(
        tripName: String,
        points: List<GpsRoutePoint>,
        vehicle: String = "Škoda Kylaq 1.0 TSI"
    ): String {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val writer = StringWriter()
        writer.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        writer.appendLine("<gpx version=\"1.1\" creator=\"KylaqOBD2Scan\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        writer.appendLine("  <metadata>")
        writer.appendLine("    <name><![CDATA[$tripName]]></name>")
        writer.appendLine("    <desc><![CDATA[Recorded with $vehicle]]></desc>")
        if (points.isNotEmpty()) {
            writer.appendLine("    <time>${isoFormat.format(Date(points.first().timestampMs))}</time>")
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
            writer.append("<time>${isoFormat.format(Date(pt.timestampMs))}</time>")
            writer.append("<extensions><speed>${String.format(Locale.US, "%.2f", pt.speedKmh / 3.6)}</speed>")
            pt.rpm?.let { rpm -> writer.append("<rpm>${rpm.toInt()}</rpm>") }
            writer.append("</extensions>")
            writer.appendLine("</trkpt>")
        }

        writer.appendLine("    </trkseg>")
        writer.appendLine("  </trk>")
        writer.appendLine("</gpx>")

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

        val coordString = points.joinToString(" ") { pt ->
            "${String.format(Locale.US, "%.6f", pt.longitude)},${String.format(Locale.US, "%.6f", pt.latitude)},${String.format(Locale.US, "%.1f", pt.altitudeM ?: 0.0)}"
        }
        writer.appendLine("          $coordString")
        writer.appendLine("        </coordinates>")
        writer.appendLine("      </LineString>")
        writer.appendLine("    </Placemark>")
        writer.appendLine("  </Document>")
        writer.appendLine("</kml>")

        return writer.toString()
    }

    /**
     * Saves GPX content to the designated export file.
     */
    fun saveGpxFile(destFile: File, tripName: String, points: List<GpsRoutePoint>): File {
        destFile.parentFile?.mkdirs()
        destFile.writeText(exportToGpx(tripName, points))
        return destFile
    }

    /**
     * Saves KML content to the designated export file.
     */
    fun saveKmlFile(destFile: File, tripName: String, points: List<GpsRoutePoint>): File {
        destFile.parentFile?.mkdirs()
        destFile.writeText(exportToKml(tripName, points))
        return destFile
    }
}
