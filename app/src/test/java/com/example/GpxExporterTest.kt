package com.example

import com.example.data.GpsRoutePoint
import com.example.data.GpxExporter
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExporterTest {

    @Test
    fun `exports valid GPX format with tracks and extensions`() {
        val points = listOf(
            GpsRoutePoint(
                timestampMs = 1726000000000L,
                latitude = 17.4483,
                longitude = 78.3915,
                altitudeM = 542.0,
                speedKmh = 45.0,
                rpm = 1800.0
            ),
            GpsRoutePoint(
                timestampMs = 1726000015000L,
                latitude = 17.4490,
                longitude = 78.3925,
                altitudeM = 543.5,
                speedKmh = 52.0,
                rpm = 2100.0
            )
        )

        val gpx = GpxExporter.exportToGpx("Trip Madhapur", points)
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("lat=\"17.448300\""))
        assertTrue(gpx.contains("lon=\"78.391500\""))
        assertTrue(gpx.contains("<ele>542.0</ele>"))
        assertTrue(gpx.contains("<speed>12.50</speed>"))
        assertTrue(gpx.contains("<rpm>1800</rpm>"))
        assertTrue(gpx.contains("</trkseg>"))
    }

    @Test
    fun `exports valid KML format with placemarks and coordinates`() {
        val points = listOf(
            GpsRoutePoint(
                timestampMs = 1726000000000L,
                latitude = 17.4483,
                longitude = 78.3915,
                altitudeM = 542.0,
                speedKmh = 45.0
            ),
            GpsRoutePoint(
                timestampMs = 1726000015000L,
                latitude = 17.4490,
                longitude = 78.3925,
                altitudeM = 543.5,
                speedKmh = 52.0
            )
        )

        val kml = GpxExporter.exportToKml("Trip Hyderabad", points)
        assertTrue(kml.contains("<kml xmlns=\"http://www.opengis.net/kml/2.2\">"))
        assertTrue(kml.contains("<LineString>"))
        assertTrue(kml.contains("78.391500,17.448300,542.0"))
        assertTrue(kml.contains("78.392500,17.449000,543.5"))
    }

    @Test
    fun `serializes and parses route points CSV round-trip`() {
        val points = listOf(
            GpsRoutePoint(
                timestampMs = 1726000000000L,
                latitude = 17.4483,
                longitude = 78.3915,
                altitudeM = 542.0,
                speedKmh = 45.0,
                rpm = 1800.0,
                isHalt = false
            ),
            GpsRoutePoint(
                timestampMs = 1726000015000L,
                latitude = 17.4490,
                longitude = 78.3925,
                altitudeM = 543.5,
                speedKmh = 0.0,
                rpm = 850.0,
                isHalt = true
            )
        )

        val tempFile = java.io.File.createTempFile("route_test", ".csv")
        try {
            GpxExporter.saveRoutePointsToCsv(tempFile, points)
            val parsed = GpxExporter.readRoutePointsFromCsv(tempFile)
            org.junit.Assert.assertEquals(2, parsed.size)
            org.junit.Assert.assertEquals(17.4483, parsed[0].latitude, 0.0001)
            org.junit.Assert.assertEquals(78.3915, parsed[0].longitude, 0.0001)
            org.junit.Assert.assertEquals(542.0, parsed[0].altitudeM!!, 0.1)
            org.junit.Assert.assertFalse(parsed[0].isHalt)
            org.junit.Assert.assertTrue(parsed[1].isHalt)
        } finally {
            tempFile.delete()
        }
    }
}
