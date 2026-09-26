package com.example

import com.example.model.DecoderType
import com.example.model.PidDefinition
import com.example.model.PollingPriority
import com.example.protocol.PidDecoder
import com.example.ui.components.SteeringAngleData
import com.example.ui.components.SteeringDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringAngleDecoderTest {

    private val pidDef01B5 = PidDefinition(
        id = "01B5",
        service = "01",
        pid = "B5",
        name = "Steering Wheel Angle",
        shortName = "Steer Ang",
        unit = "°",
        dataBytes = 2,
        decoderType = DecoderType.STEERING_ANGLE_SIGNED_10,
        formulaDisplay = "Signed16(A, B) / 10.0",
        description = "Live steering wheel angle (degrees, signed: Left negative / Right positive)",
        priority = PollingPriority.FAST,
        defaultIntervalMs = 150L
    )

    private val pidDef220200 = PidDefinition(
        id = "220200",
        service = "22",
        pid = "0200",
        name = "EPS Steering Wheel Angle (UDS)",
        shortName = "EPS Angle",
        unit = "°",
        canHeader = "714",
        expectedRxId = "77E",
        dataBytes = 2,
        decoderType = DecoderType.STEERING_ANGLE_SIGNED_10,
        formulaDisplay = "Signed16(A, B) / 10.0",
        description = "UDS EPS G85 steering wheel angle from Module 44",
        priority = PollingPriority.FAST,
        defaultIntervalMs = 150L
    )

    @Test
    fun `decodes right turn positive steering angle accurately`() {
        // 0x41 0xB5 0x00 0xA4 -> 0x00A4 = 164 -> +16.4 degrees
        val payload = listOf(0x41, 0xB5, 0x00, 0xA4)
        val result = PidDecoder.decode(pidDef01B5, payload)

        assertTrue(result.isKnown)
        assertEquals(16.4, result.numericValue ?: 0.0, 0.001)
        assertTrue(result.displayValue.contains("+16.4° (Right)"))
        assertEquals("°", result.unit)
    }

    @Test
    fun `decodes left turn negative steering angle accurately via two's complement`() {
        // 0x41 0xB5 0xFF 0x5C -> 0xFF5C = 65372 - 65536 = -164 -> -16.4 degrees
        val payload = listOf(0x41, 0xB5, 0xFF, 0x5C)
        val result = PidDecoder.decode(pidDef01B5, payload)

        assertTrue(result.isKnown)
        assertEquals(-16.4, result.numericValue ?: 0.0, 0.001)
        assertTrue(result.displayValue.contains("-16.4° (Left)"))
        assertEquals("°", result.unit)
    }

    @Test
    fun `decodes dead-center zero steering angle`() {
        // 0x41 0xB5 0x00 0x00 -> 0.0 degrees
        val payload = listOf(0x41, 0xB5, 0x00, 0x00)
        val result = PidDecoder.decode(pidDef01B5, payload)

        assertTrue(result.isKnown)
        assertEquals(0.0, result.numericValue ?: 0.0, 0.001)
        assertTrue(result.displayValue.contains("+0.0° (Center)"))
    }

    @Test
    fun `rejects sentinel uncalibrated or error values without emitting fake data`() {
        // 0x7FFF = 32767 -> Sentinel uncalibrated marker
        val payload7FFF = listOf(0x41, 0xB5, 0x7F, 0xFF)
        val result7FFF = PidDecoder.decode(pidDef01B5, payload7FFF)
        assertFalse(result7FFF.isKnown)
        assertNull(result7FFF.numericValue)
        assertTrue(result7FFF.displayValue.contains("uncalibrated"))

        // 0x8000 = -32768 -> Sentinel hardware failure marker
        val payload8000 = listOf(0x41, 0xB5, 0x80, 0x00)
        val result8000 = PidDecoder.decode(pidDef01B5, payload8000)
        assertFalse(result8000.isKnown)
        assertNull(result8000.numericValue)

        // 0xFFFF = Not available
        val payloadFFFF = listOf(0x41, 0xB5, 0xFF, 0xFF)
        val resultFFFF = PidDecoder.decode(pidDef01B5, payloadFFFF)
        assertFalse(resultFFFF.isKnown)
        assertNull(resultFFFF.numericValue)
    }

    @Test
    fun `steering angle data correctly computes road wheel angle and turning radius`() {
        val data = SteeringAngleData(
            rawAngleDeg = 145.0,
            isFresh = true,
            isConnected = true,
            steeringRatio = 14.5
        )

        assertTrue(data.isValid)
        assertEquals(SteeringDirection.RIGHT, data.direction)
        assertEquals(10.0, data.roadWheelAngleDeg ?: 0.0, 0.001) // 145 / 14.5 = 10.0 degrees

        // Turning radius = 2.566 / tan(10°) ≈ 14.55 m
        val radius = data.estimatedTurnRadiusM ?: 0.0
        assertEquals(14.55, radius, 0.1)
    }

    @Test
    fun `decodes UDS Service 0x22 DID 0200 positive response from EPS Module 44`() {
        // UDS 0x22 Response: 0x62 0x02 0x00 0x01 0x04 -> 0x0104 = 260 -> +26.0 degrees (Right)
        val udsPayload = listOf(0x62, 0x02, 0x00, 0x01, 0x04)
        val result = PidDecoder.decode(pidDef220200, udsPayload)

        assertTrue(result.isKnown)
        assertEquals(26.0, result.numericValue ?: 0.0, 0.001)
        assertTrue(result.displayValue.contains("+26.0° (Right)"))
        assertEquals("°", result.unit)
    }

    @Test
    fun `decodes UDS Service 0x22 DID 0200 negative turn angle from EPS Module 44`() {
        // UDS 0x22 Response: 0x62 0x02 0x00 0xFE 0xFC -> 0xFEFC = 65276 - 65536 = -260 -> -26.0 degrees (Left)
        val udsPayload = listOf(0x62, 0x02, 0x00, 0xFE, 0xFC)
        val result = PidDecoder.decode(pidDef220200, udsPayload)

        assertTrue(result.isKnown)
        assertEquals(-26.0, result.numericValue ?: 0.0, 0.001)
        assertTrue(result.displayValue.contains("-26.0° (Left)"))
    }

    @Test
    fun `rejects UDS Service 0x22 negative response 7F 22 NRC without emitting false data`() {
        // UDS 0x7F 0x22 0x31 (Request Out of Range)
        val negativeResponse = listOf(0x7F, 0x22, 0x31)
        val result = PidDecoder.decode(pidDef220200, negativeResponse)

        assertFalse(result.isKnown)
        assertNull(result.numericValue)
        assertEquals("INVALID_RESPONSE", result.displayValue)
    }

    @Test
    fun `steering angle data marks disconnected or stale data as not valid`() {
        val staleData = SteeringAngleData(
            rawAngleDeg = 25.0,
            isFresh = false, // Stale (> 1.5s)
            isConnected = true
        )
        assertFalse(staleData.isValid)

        val disconnectedData = SteeringAngleData(
            rawAngleDeg = 25.0,
            isFresh = true,
            isConnected = false
        )
        assertFalse(disconnectedData.isValid)
    }
}
