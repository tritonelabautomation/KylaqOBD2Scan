package com.example

import com.example.analysis.ZoomWindow
import com.example.analysis.zoomWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinch-zoom window math (owner pipeline task 2) - pure, gesture-independent. */
class TrendZoomTest {

    @Test
    fun `pinch open halves the span and keeps the centroid instant anchored`() {
        val w = zoomWindow(ZoomWindow.FULL, centroidFrac = 0.5, zoomFactor = 2.0, panFrac = 0.0)
        assertEquals(0.5, w.span, 1e-9)
        // The instant under the centroid (mid-trip) is still under the centroid.
        assertEquals(0.5, w.startFrac + 0.5 * w.span, 1e-9)
    }

    @Test
    fun `anchor holds for off-centre pinches too`() {
        val w = zoomWindow(ZoomWindow.FULL, centroidFrac = 0.25, zoomFactor = 4.0, panFrac = 0.0)
        assertEquals(0.25, w.span, 1e-9)
        assertEquals(0.25, w.startFrac + 0.25 * w.span, 1e-9)
    }

    @Test
    fun `zooming out from the full span stays clamped at the full trip`() {
        val w = zoomWindow(ZoomWindow.FULL, centroidFrac = 0.5, zoomFactor = 0.4, panFrac = 0.0)
        assertEquals(ZoomWindow.FULL, w)
    }

    @Test
    fun `span never goes below the floor no matter how hard the pinch`() {
        val w = zoomWindow(ZoomWindow.FULL, centroidFrac = 0.5, zoomFactor = 10_000.0, panFrac = 0.0)
        assertEquals(ZoomWindow.MIN_SPAN, w.span, 1e-9)
    }

    @Test
    fun `pan slides the window and content follows the fingers`() {
        val zoomed = ZoomWindow(0.4, 0.6)
        val right = zoomWindow(zoomed, centroidFrac = 0.5, zoomFactor = 1.0, panFrac = 0.1)
        assertEquals(0.2, right.span, 1e-9)
        assertTrue("fingers right -> window left", right.startFrac < zoomed.startFrac)
        // Pan is proportional to the VISIBLE span: dragging the full plot width moves the
        // window exactly one screenful (1:1 feel). 0.1 of a screen -> 0.1 * 0.2 = 0.02.
        assertEquals(0.38, right.startFrac, 1e-9)
    }

    @Test
    fun `window is clamped inside the trip with its span preserved`() {
        val farLeft = zoomWindow(ZoomWindow(0.05, 0.25), centroidFrac = 0.5, zoomFactor = 1.0, panFrac = 5.0)
        assertEquals(0.2, farLeft.span, 1e-9)
        assertEquals(0.0, farLeft.startFrac, 1e-9)

        val farRight = zoomWindow(ZoomWindow(0.75, 0.95), centroidFrac = 0.5, zoomFactor = 1.0, panFrac = -5.0)
        assertEquals(0.2, farRight.span, 1e-9)
        assertEquals(1.0, farRight.endFrac, 1e-9)
    }

    @Test
    fun `repeated pinches then reset-friendly bounds - deep zoom stays legal`() {
        var w = ZoomWindow.FULL
        repeat(30) { w = zoomWindow(w, centroidFrac = 0.7, zoomFactor = 1.5, panFrac = 0.0) }
        assertEquals(ZoomWindow.MIN_SPAN, w.span, 1e-9)
        assertTrue(w.startFrac >= 0.0 && w.endFrac <= 1.0)
    }
}
