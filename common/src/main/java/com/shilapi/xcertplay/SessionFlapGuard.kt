package com.shilapi.xcertplay

import android.content.Context

/**
 * S11: caps automatic reconnects when sessions keep dying.
 *
 * Three genuine session deaths within ten minutes stop automatic reconnects; the driver
 * must tap the manual retry button. The death timestamps live in SharedPreferences, so the
 * count survives Activity recreation — unlike [WirelessStartupRetryBudget], whose in-memory
 * retry count resets with the Activity and would let a flapping session reconnect forever.
 *
 * Only genuine losses count: the host records a death from `onSessionEnded` /
 * `onTransportError` only. Deliberate restarts (cluster toggles, USB switch, settings-saved
 * reconnect, manual retry) never reach those handlers with a live session, so they are not
 * counted. A session that stays up lets old deaths age out of the ten-minute window.
 *
 * One physical loss can report twice — a tunnel failure reports `onTransportError` and the
 * session close that follows reports `onSessionEnded` — so deaths that arrive within
 * [MIN_DEATH_INTERVAL_MILLIS] of the previous one count as the same loss. A new session
 * cannot start and die again that fast: the minimum reconnect delay is already that long.
 */
internal class SessionFlapGuard(
    private val loadDeaths: () -> List<Long>,
    private val saveDeaths: (List<Long>) -> Unit,
    private val nowMillis: () -> Long,
) {
    /**
     * Records one genuine session death now. Returns true when the cap trips, i.e. this is
     * the [MAX_DEATHS]th death inside the window.
     */
    fun recordDeath(): Boolean {
        val now = nowMillis()
        val deaths = recentDeaths(now)
        if (deaths.lastOrNull()?.let { now - it < MIN_DEATH_INTERVAL_MILLIS } == true) {
            return deaths.size >= MAX_DEATHS
        }
        val updated = deaths + now
        saveDeaths(updated)
        return updated.size >= MAX_DEATHS
    }

    /** True when the cap already tripped inside the window. Stale deaths are pruned on read. */
    fun isTripped(): Boolean = recentDeaths(nowMillis()).size >= MAX_DEATHS

    /** Manual retry: forgets the deaths so automatic reconnects may resume. */
    fun clear() = saveDeaths(emptyList())

    private fun recentDeaths(now: Long): List<Long> =
        loadDeaths().filter { it <= now && now - it < WINDOW_MILLIS }

    companion object {
        const val MAX_DEATHS = 3
        const val WINDOW_MILLIS = 10 * 60_000L

        /** A new session cannot start and die again faster than the minimum reconnect delay. */
        const val MIN_DEATH_INTERVAL_MILLIS = 2_000L

        private const val PREFS_NAME = "xcertplay_airplay"
        private const val KEY_DEATH_MILLIS = "session_flap_death_millis"

        /**
         * Production wiring: the same SharedPreferences file AirPlayPersistence uses. The
         * [nowMillis] seam exists so tests can drive the clock; production uses the wall
         * clock, because the timestamps must stay comparable across process restarts.
         */
        fun fromContext(
            context: Context,
            nowMillis: () -> Long = System::currentTimeMillis,
        ): SessionFlapGuard {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return SessionFlapGuard(
                loadDeaths = {
                    prefs.getString(KEY_DEATH_MILLIS, null)
                        ?.split(",")
                        ?.mapNotNull { it.toLongOrNull() }
                        .orEmpty()
                },
                saveDeaths = { deaths ->
                    prefs.edit().putString(KEY_DEATH_MILLIS, deaths.joinToString(",")).apply()
                },
                nowMillis = nowMillis,
            )
        }
    }
}
