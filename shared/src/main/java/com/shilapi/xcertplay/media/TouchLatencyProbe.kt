package com.shilapi.xcertplay.media

/** Diagnostic link between the touch uplink and the next video frame, read by [VideoStats]. */
internal object TouchLatencyProbe {
    @Volatile private var pendingTouchNs = 0L
    @Volatile private var pendingShownTouchNs = 0L
    @Volatile var maxSendNs = 0L

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (pendingTouchNs == 0L) pendingTouchNs = sentAtNs
        if (pendingShownTouchNs == 0L) pendingShownTouchNs = sentAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    /** Returns touch-to-frame latency for the first frame after a touch, or -1. */
    fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs
        if (touch == 0L) return -1
        pendingTouchNs = 0L
        return nowNs - touch
    }

    /**
     * Returns touch-to-shown latency for the first rendered frame after a touch, or -1.
     * Unlike [onFrame], which ends when the first packet reaches the decoder, this ends when
     * the frame is actually shown, so it includes decode (~40ms) and the iPhone's reaction.
     */
    fun onRendered(nowNs: Long): Long {
        val touch = pendingShownTouchNs
        if (touch == 0L) return -1
        pendingShownTouchNs = 0L
        return nowNs - touch
    }

    /**
     * Drops a touch that outlived its session. A pending touch is otherwise consumed by the
     * next session's first frame and reported as a phantom 30s+ latency (samples of 30493ms,
     * 99615ms and 112250ms were all stale touches from before the session started).
     * No timeout is applied here: real arrival gaps reach 1944ms, so a 2s timeout would
     * delete genuine samples.
     */
    fun clear() {
        pendingTouchNs = 0L
        pendingShownTouchNs = 0L
    }
}
