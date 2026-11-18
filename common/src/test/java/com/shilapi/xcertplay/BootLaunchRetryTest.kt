package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class BootLaunchRetryTest {
    @Test fun retriesTwiceAtFifteenSecondIntervalsThenStops() {
        assertEquals(15_000L, BootLaunchRetry.delayForRetry(0))
        assertEquals(15_000L, BootLaunchRetry.delayForRetry(1))
        assertNull(BootLaunchRetry.delayForRetry(2))
        assertNull(BootLaunchRetry.delayForRetry(3))
    }

    @Test fun retryNeedsAnUnseenActivityAndAttemptsLeft() {
        assertTrue(BootLaunchRetry.shouldRetry(0L, 0))
        assertTrue(BootLaunchRetry.shouldRetry(0L, 1))
        assertFalse(BootLaunchRetry.shouldRetry(0L, 2))
        assertFalse(BootLaunchRetry.shouldRetry(1_700_000_000_000L, 0))
        assertFalse(BootLaunchRetry.shouldRetry(1_700_000_000_000L, 1))
    }
}
