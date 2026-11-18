package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class StreamReceiveStatsTest {
    @Test fun linksSequenceGapsToTheSameLongReadAndResetsOnlyWindowCounters() {
        var clock = 1L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        fun packet(sequence: Int, readMs: Long) {
            stats.reading()
            clock += readMs * 1_000_000L
            stats.received(100, sequence)
            stats.processed()
        }
        packet(1, 10)
        packet(4, 300) // Two missing packets follow this long socket wait.
        packet(7, 10) // Two more missing packets follow a short socket wait.
        packet(8, 400) // A long read alone does not imply packet loss.
        stats.flush(ended = true)
        assertEquals(1, output.size)
        assertTrue(output.single().contains("windowMs=720 readsOver250Ms=2 seqGapAfterReadOver250Ms=1 seqMissingAfterReadOver250Ms=2"))
        packet(9, 10)
        stats.flush(ended = true)
        assertTrue(output.last().contains("windowMs=10 readsOver250Ms=0 seqGapAfterReadOver250Ms=0 seqMissingAfterReadOver250Ms=0"))
        assertTrue(output.last().contains("seqForwardGaps=0"))
    }

    @Test fun separatesSocketWaitFromLocalProcessingAndTracksSequenceWrap() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        fun packet(sequence: Int) {
            stats.reading()
            clock += 400_000_000L
            stats.received(100, sequence)
            clock += 200_000L
            stats.processed()
        }
        packet(65535)
        packet(0)
        packet(3) // two missing, then one late packet arrives
        packet(2)
        packet(4)
        stats.flush(ended = true)
        assertEquals(1, output.size)
        assertTrue(output.single().contains("readMaxMs=400 processMaxUs=200 seqForwardGaps=2 lateOrDuplicate=1"))
        assertTrue(output.single().contains("packets=5 bytes=500"))
    }

    @Test fun reportResetsWindowButRetainsSequenceContinuity() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        stats.reading()
        clock = 5_000_000_000L
        stats.received(10, 1)
        stats.processed()
        stats.reading()
        stats.received(20, 3)
        stats.processed()
        stats.flush(true)
        assertEquals(2, output.size)
        assertTrue(output.last().contains("packets=1 bytes=20 readMaxMs=0"))
        assertTrue(output.last().contains("seqForwardGaps=1"))
    }

    @Test fun decryptTimingIsReportedOnlyWhenRecordedAndThenReset() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("video", output::add) { clock }
        stats.received(1_000)
        stats.decrypted(2_000_000, 500_000)
        stats.decrypted(500_000, 250_000)
        stats.processed()
        clock = 5_000_000_000L
        stats.flush()
        assertTrue(output.last().endsWith(" decryptAvgUs=1250 decryptMaxUs=2000 decryptMBps=300"))
        clock = 10_000_000_000L
        stats.flush()
        assertTrue(!output.last().contains("decrypt"))
    }

    @Test fun senderVsArrivalGapSeparatesSenderStallFromReceiveStall() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("video", output::add) { clock }
        fun frame(senderMs: Long, readMs: Long) {
            stats.reading()
            clock += readMs * 1_000_000L
            stats.received(1_000)
            stats.frameTiming(senderMs * 1_000_000L, clock)
            stats.processed()
        }
        frame(senderMs = 1_000, readMs = 10) // first frame: no previous, no line
        frame(senderMs = 1_010, readMs = 10) // 10ms gap: below the 250ms threshold
        frame(senderMs = 1_026, readMs = 1_600) // iPhone kept sending; arrival stalled 1600ms
        assertEquals(1, output.size)
        assertTrue(output.single().contains("senderVsArrival arrivalGapMs=1600 senderGapMs=16 readMs=1600"))
        frame(senderMs = 2_626, readMs = 1_500) // iPhone stopped sending too: sender gap tracks arrival
        assertEquals(2, output.size)
        assertTrue(output.last().contains("senderVsArrival arrivalGapMs=1500 senderGapMs=1600 readMs=1500"))
    }

    @Test fun unknownSenderTimeStillLogsTheArrivalGap() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("video", output::add) { clock }
        stats.reading()
        clock = 10_000_000L
        stats.received(1_000)
        stats.frameTiming(1_000_000_000L, clock)
        stats.processed()
        stats.reading()
        clock = 1_610_000_000L
        stats.received(1_000)
        stats.frameTiming(0L, clock)
        stats.processed()
        assertEquals(1, output.size)
        assertTrue(output.single().contains("senderVsArrival arrivalGapMs=1600 senderGapMs=unknown readMs=1600"))
    }

    @Test fun frameGapCountersAreKeptInTheWindowSummary() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("video", output::add) { clock }
        fun frame(senderMs: Long, readMs: Long) {
            stats.reading()
            clock += readMs * 1_000_000L
            stats.received(1_000)
            stats.frameTiming(senderMs * 1_000_000L, clock)
            stats.processed()
        }
        frame(senderMs = 1_000, readMs = 10)
        frame(senderMs = 1_010, readMs = 10)
        frame(senderMs = 1_026, readMs = 1_600) // arrival stalled 1600ms, sender kept sending
        stats.flush(ended = true)
        assertTrue(output.last().contains("frameGapMaxMs=1600 senderGapMaxMs=16 frameTimingCalls=3"))
        // Counters reset per window, but gap continuity across the flush is kept.
        frame(senderMs = 2_626, readMs = 10)
        stats.flush(ended = true)
        assertTrue(output.last().contains("frameGapMaxMs=10 senderGapMaxMs=1600 frameTimingCalls=1"))
    }
}
