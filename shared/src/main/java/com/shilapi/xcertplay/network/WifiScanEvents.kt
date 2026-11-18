package com.shilapi.xcertplay.network

/**
 * Process-wide record of the last Wi-Fi scan the system reported, for the video gap log.
 *
 * Android gives apps no way to ask when a scan starts, and on this head unit there is no ADB,
 * so [WifiScanPause] cannot observe scans either. The only observable is the
 * `SCAN_RESULTS_AVAILABLE_ACTION` broadcast, which fires when a scan *completes* — so the
 * recorded time is a completion, not a start, and a scan that never completes stays invisible.
 * A gap whose `scanAgeMs` is small (a scan just finished) is the closest this device gets to
 * "a scan ran during the gap"; a large age rules scans out.
 */
internal object WifiScanEvents {
    @Volatile private var lastCompletedNs = 0L

    fun onScanCompleted(nowNs: Long = System.nanoTime()) {
        lastCompletedNs = nowNs
    }

    /** Milliseconds since the last scan completed, or null when none was observed. */
    fun ageMs(nowNs: Long): Long? {
        val last = lastCompletedNs
        if (last == 0L) return null
        return (nowNs - last) / 1_000_000
    }

    fun describe(nowNs: Long): String =
        ageMs(nowNs)?.let { "scanAgeMs=$it" } ?: "scanAgeMs=none"
}

/**
 * Radio state at the moment a video gap is logged, supplied by whoever owns the Wi-Fi
 * radio ([WifiScanObserver]). Read on the video receive thread, so the provider must be
 * cheap and never throw; failures fall back to `radio=unknown`.
 */
internal object VideoGapRadioState {
    fun interface Snapshot {
        fun snapshot(): String
    }

    @Volatile var provider: Snapshot? = null

    fun describe(): String =
        runCatching { provider?.snapshot() }.getOrNull() ?: "radio=unknown"
}
