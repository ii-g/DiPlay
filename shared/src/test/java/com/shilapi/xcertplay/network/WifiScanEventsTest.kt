package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiScanEventsTest {
    @Test fun scanAgeCountsFromTheLastRecordedCompletion() {
        WifiScanEvents.onScanCompleted(nowNs = 1_000_000_000L)
        assertEquals(500L, WifiScanEvents.ageMs(1_500_000_000L))
        assertTrue(WifiScanEvents.describe(1_500_000_000L).contains("scanAgeMs=500"))
    }

    @Test fun radioSnapshotComesFromTheRegisteredProvider() {
        VideoGapRadioState.provider =
            VideoGapRadioState.Snapshot { "radio=wifiEnabled=true freqMHz=2412 ssid=test" }
        assertEquals(
            "radio=wifiEnabled=true freqMHz=2412 ssid=test",
            VideoGapRadioState.describe(),
        )
        VideoGapRadioState.provider = null
        assertEquals("radio=unknown", VideoGapRadioState.describe())
    }
}
