package com.example.analysis

/**
 * Pinch-zoom window math for the trend chart (owner 2026-09-16 pipeline task 2:
 * "Pinch zoom in zoom out"). Pure JVM so the gesture logic is unit-testable without
 * Compose: the visible TIME window is expressed as fractions of the full trip domain,
 * so zoom/pan never depends on pixel sizes.
 *
 * Conventions (matching what fingers expect):
 *  - `zoomFactor` > 1 pinches OPEN (zoom in: span shrinks by the factor);
 *  - the pinch CENTROID stays anchored on the same instant while zooming;
 *  - `panFrac` > 0 means fingers moved RIGHT, so the window slides LEFT (content
 *    follows the hand);
 *  - the window is always clamped inside [0, 1] with its span preserved, and the span
 *    is floored at [ZoomWindow.MIN_SPAN] so nobody zooms into a single sample and
 *    ceilinged at 1 (the full trip).
 */
data class ZoomWindow(val startFrac: Double, val endFrac: Double) {
    val span: Double get() = endFrac - startFrac

    companion object {
        val FULL = ZoomWindow(0.0, 1.0)

        /** 1% of a 70-minute drive is still 42 s - deep enough, never absurd. */
        const val MIN_SPAN = 0.01
    }
}

/** Apply one pinch/pan gesture event to [w]; see file KDoc for sign conventions. */
fun zoomWindow(
    w: ZoomWindow,
    centroidFrac: Double,
    zoomFactor: Double,
    panFrac: Double,
    minSpan: Double = ZoomWindow.MIN_SPAN
): ZoomWindow {
    val cf = centroidFrac.coerceIn(0.0, 1.0)
    val anchor = w.startFrac + cf * w.span
    val span = (w.span / zoomFactor.coerceIn(0.01, 100.0)).coerceIn(minSpan, 1.0)
    val start = (anchor - cf * span - panFrac * span).coerceIn(0.0, 1.0 - span)
    return ZoomWindow(start, start + span)
}
