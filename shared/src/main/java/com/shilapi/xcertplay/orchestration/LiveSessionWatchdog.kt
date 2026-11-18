package com.shilapi.xcertplay.orchestration

/**
 * Detects a live CarPlay session whose video path has selectively died, and logs it.
 * Detect + log only: no reconnect, no keyframe request, no tunnel keepalive.
 *
 * Starvation is measured from frame ARRIVALS on the receive side (the arrival time
 * [ScreenStream] records when a frame body finishes arriving), never from displayed
 * frames: when the driver leaves the car or the screen is off, the app deliberately
 * "parks" decoder output on an offscreen surface, so shown==0 while video still
 * arrives and decodes. Only a gap in arrivals counts as starvation.
 *
 * Compound firing condition: video starved AND audio alive AND control alive. That
 * signature is the video path selectively dead while the session is otherwise alive;
 * the whole-link-dead case needs no watchdog — the control socket aborts on its own
 * (TcpLiveness: 10 s idle, 3 probes, 20 s user timeout) and the session closes.
 *
 * The idle threshold is a second, shorter tripwire inside the same watchdog: a video
 * gap past it logs an idle line even when audio/control are not both alive, so a
 * quiet session still leaves evidence. It must sit above the natural gap range —
 * real arrival gaps reached 1944 ms in device reports — so 5 s, not 500 ms, which
 * would false-fire constantly.
 *
 * Phone calls do not false-fire the 10 s tripwire: during a call the iPhone still
 * trickles video (~4 fps observed), so a 10 s no-frame gap does not occur in calls.
 *
 * Pure logic, no Android dependencies: [nowNanos], [schedule] and [log] are injected
 * like [FirstTcpWatchdog]. Arm only while a session is active; disarm on session end.
 */
internal class LiveSessionWatchdog(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val nowNanos: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
    private val checkIntervalMs: Long = CHECK_INTERVAL_MS,
    private val idleThresholdNs: Long = IDLE_THRESHOLD_NS,
    private val starvationThresholdNs: Long = STARVATION_THRESHOLD_NS,
    private val audioAliveNs: Long = AUDIO_ALIVE_NS,
    private val controlAliveNs: Long = CONTROL_ALIVE_NS,
) {
    private var armedSession: Any? = null
    private var controlLastActivityNs: () -> Long = { 0L }
    private var lastVideoArrivalNs = 0L
    private var lastAudioArrivalNs = 0L
    private var idleLogged = false
    private var starvedLogged = false
    private var cancelTimer: (() -> Unit)? = null

    val isArmed: Boolean get() = armedSession != null

    /**
     * Arms the watchdog for [session] (an identity token, normally the AirPlaySession).
     * The video baseline is the arm time, so video that never starts trips idle at
     * [idleThresholdNs] and starvation at [starvationThresholdNs]. Re-arming replaces
     * any previous session and resets both latches.
     */
    @Synchronized fun arm(session: Any, controlLastActivityNs: () -> Long) {
        cancel()
        armedSession = session
        this.controlLastActivityNs = controlLastActivityNs
        lastVideoArrivalNs = nowNanos()
        lastAudioArrivalNs = 0L
        idleLogged = false
        starvedLogged = false
        log("live-session watchdog armed")
        cancelTimer = schedule(checkIntervalMs, ::check)
    }

    /** Disarms only if [session] is the armed one, so a stale close cannot disarm a new session. */
    @Synchronized fun disarm(session: Any): Boolean {
        if (armedSession !== session) return false
        armedSession = null
        cancel()
        return true
    }

    /** A video frame arrived on the receive side; ignored unless [session] is the armed one. */
    @Synchronized fun noteVideoFrame(session: Any, arrivalNanos: Long) {
        if (armedSession !== session) return
        lastVideoArrivalNs = arrivalNanos
        idleLogged = false
        starvedLogged = false
    }

    /** An audio packet arrived (any audio stream); ignored unless [session] is the armed one. */
    @Synchronized fun noteAudioPacket(session: Any, atNanos: Long) {
        if (armedSession !== session) return
        lastAudioArrivalNs = atNanos
    }

    private fun check() {
        val messages = mutableListOf<String>()
        synchronized(this) {
            // Disarm holds the same lock, so armedSession cannot change mid-check.
            if (armedSession == null) return
            val now = nowNanos()
            val videoGapNs = now - lastVideoArrivalNs
            val audioGapNs = if (lastAudioArrivalNs == 0L) Long.MAX_VALUE else now - lastAudioArrivalNs
            val controlGapNs = now - controlLastActivityNs()
            if (videoGapNs >= idleThresholdNs && !idleLogged) {
                idleLogged = true
                messages += "live-session video idle videoGapMs=${videoGapNs / 1_000_000} " +
                    "audioGapMs=${gapMs(audioGapNs)} controlGapMs=${controlGapNs / 1_000_000} " +
                    "idleThresholdMs=${idleThresholdNs / 1_000_000}"
            }
            // Audio RTP arrives about every 20 ms while any audio stream is up, so 5 s of
            // silence means the audio path is dead or no audio stream exists; control traffic
            // is sparse (user input, feedback polls), so its window is deliberately wide at
            // 60 s — a vanished peer aborts the control socket within ~20 s anyway.
            val audioAlive = audioGapNs <= audioAliveNs
            val controlAlive = controlGapNs <= controlAliveNs
            if (videoGapNs >= starvationThresholdNs && !starvedLogged && audioAlive && controlAlive) {
                starvedLogged = true
                messages += "live-session VIDEO STARVED videoGapMs=${videoGapNs / 1_000_000} " +
                    "audioGapMs=${gapMs(audioGapNs)} controlGapMs=${controlGapNs / 1_000_000} " +
                    "starvationThresholdMs=${starvationThresholdNs / 1_000_000} " +
                    "audioAlive=true controlAlive=true"
            }
            cancelTimer = schedule(checkIntervalMs, ::check)
        }
        messages.forEach(log)
    }

    private fun gapMs(gapNs: Long): String =
        if (gapNs == Long.MAX_VALUE) "never" else "${gapNs / 1_000_000}"

    private fun cancel() { cancelTimer?.invoke(); cancelTimer = null }

    companion object {
        /** One check per second: starvation is detected within ~10–11 s of the last frame. */
        const val CHECK_INTERVAL_MS = 1_000L
        /** Above the worst natural arrival gap seen on device (1944 ms); 500 ms would false-fire. */
        const val IDLE_THRESHOLD_NS = 5_000_000_000L
        /** Video selectively dead while the session is otherwise alive. */
        const val STARVATION_THRESHOLD_NS = 10_000_000_000L
        /** Audio RTP arrives ~every 20 ms while a stream is up; 5 s of silence is dead/absent. */
        const val AUDIO_ALIVE_NS = 5_000_000_000L
        /** Control traffic is sparse; a vanished peer aborts the socket within ~20 s anyway. */
        const val CONTROL_ALIVE_NS = 60_000_000_000L
    }
}
