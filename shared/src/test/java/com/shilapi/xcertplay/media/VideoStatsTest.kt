package com.shilapi.xcertplay.media

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoStatsTest {
    private val ms = 1_000_000L

    @Test fun logLineReportsTouch2frameAndTouch2shown() {
        var now = 0L
        val stats = VideoStats(nanoTime = { now })
        TouchLatencyProbe.clear()
        now = 1 * ms
        TouchLatencyProbe.onTouchSent(now, 1 * ms)
        now = 41 * ms
        stats.onReceived(100)
        now = 91 * ms
        stats.onRendered()
        now = 5_000 * ms + 91 * ms
        val line = stats.logIfDue()
        assertNotNull(line)
        assertTrue(line!!, line.contains("touch2frame avg=40ms max=40ms n=1"))
        assertTrue(line, line.contains("touch2shown avg=90ms max=90ms n=1"))
    }
}
