package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchLatencyProbeTest {
    private val ms = 1_000_000L

    @Test fun touch2frameEndsAtFirstFrameAndTouch2shownAtFirstRender() {
        TouchLatencyProbe.clear()
        TouchLatencyProbe.onTouchSent(1_000 * ms, 1 * ms)
        assertEquals(40 * ms, TouchLatencyProbe.onFrame(1_040 * ms))
        // The shown sample survives the frame arrival and ends at the first render.
        assertEquals(90 * ms, TouchLatencyProbe.onRendered(1_090 * ms))
        assertEquals(-1, TouchLatencyProbe.onFrame(2_000 * ms))
        assertEquals(-1, TouchLatencyProbe.onRendered(2_000 * ms))
    }

    @Test fun clearDropsAStaleTouchSoTheNextSessionSeesNoPhantomSample() {
        TouchLatencyProbe.clear()
        TouchLatencyProbe.onTouchSent(1_000 * ms, 1 * ms)
        // Session ends with the touch still pending, then a new session starts.
        TouchLatencyProbe.clear()
        assertEquals(-1, TouchLatencyProbe.onFrame(100_000 * ms))
        assertEquals(-1, TouchLatencyProbe.onRendered(100_000 * ms))
    }

    @Test fun aSecondTouchWhileOneIsPendingDoesNotReplaceIt() {
        TouchLatencyProbe.clear()
        TouchLatencyProbe.onTouchSent(1_000 * ms, 1 * ms)
        TouchLatencyProbe.onTouchSent(1_010 * ms, 1 * ms)
        assertEquals(40 * ms, TouchLatencyProbe.onFrame(1_040 * ms))
        assertEquals(90 * ms, TouchLatencyProbe.onRendered(1_090 * ms))
    }
}
