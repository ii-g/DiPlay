package com.shilapi.xcertplay

/**
 * Schedule for the boot auto-start retries.
 *
 * The first launch attempt happens in [BootReceiver] right after BOOT_COMPLETED. When the
 * firmware drops it, the boot-started [DiPlaySessionService] re-checks after a delay: if
 * [StartupDiagnosticSnapshot] still shows no activity resume since boot, it launches
 * [DiPlayActivity] again. At most [MAX_RETRIES] retries, each [RETRY_DELAY_MS] after the
 * previous check, so the last attempt lands about 30 seconds after boot. Pure schedule —
 * no Android calls — so unit tests can pin it down.
 */
internal object BootLaunchRetry {
    /** Delay between the boot launch (or a retry) and the next retry check. */
    const val RETRY_DELAY_MS = 15_000L
    /** Retries after the initial launch attempt in [BootReceiver]. */
    const val MAX_RETRIES = 2

    /** Delay before the next retry check once [attemptsMade] retries have been made, or null when done. */
    fun delayForRetry(attemptsMade: Int): Long? =
        if (attemptsMade < MAX_RETRIES) RETRY_DELAY_MS else null

    /** True when another retry is still allowed and the activity has not been seen since boot. */
    fun shouldRetry(activitySeenAtMs: Long, attemptsMade: Int): Boolean =
        activitySeenAtMs == 0L && attemptsMade < MAX_RETRIES
}
