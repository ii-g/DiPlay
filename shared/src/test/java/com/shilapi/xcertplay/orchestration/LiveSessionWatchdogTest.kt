package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test

class LiveSessionWatchdogTest {
    private var now = 0L // milliseconds
    private var timer: (() -> Unit)? = null
    private var controlAt = 0L // milliseconds
    private val logs = mutableListOf<String>()
    private val session = Any()
    private val otherSession = Any()

    private fun watchdog() = LiveSessionWatchdog(
        schedule = { _, action ->
            timer = action
            val cancel: () -> Unit = { timer = null }
            cancel
        },
        nowNanos = { now * 1_000_000 },
        log = logs::add,
    )

    /** Advances the fake clock to [atMs] and runs every check the watchdog schedules on the way. */
    private fun advanceTo(atMs: Long) {
        while (now < atMs) {
            now += LiveSessionWatchdog.CHECK_INTERVAL_MS
            val tick = timer ?: return
            timer = null
            tick()
        }
    }

    @Test fun disarmedWatchdogNeverFires() {
        watchdog()
        advanceTo(60_000)
        assertTrue(logs.isEmpty())
        assertNull(timer)
    }

    @Test fun steadyFramesNeverLog() {
        val watch = watchdog()
        watch.arm(session) { controlAt * 1_000_000 }
        repeat(1800) { index -> // 60 s at ~33 ms/frame, ticking the watchdog about once a second
            now += 33
            watch.noteVideoFrame(session, now * 1_000_000)
            watch.noteAudioPacket(session, now * 1_000_000)
            controlAt = now
            if (index % 30 == 29) timer?.let { tick -> timer = null; tick() }
        }
        assertFalse(logs.any { it.contains("idle") || it.contains("STARVED") })
    }

    @Test fun idleLogsAtFiveSecondsAndStarvationAtTenWithAudioAndControlAlive() {
        val watch = watchdog()
        watch.arm(session) { controlAt * 1_000_000 }
        // Video stops at t=0; audio and control keep arriving every second.
        repeat(12) {
            now += 1_000
            watch.noteAudioPacket(session, now * 1_000_000)
            controlAt = now
            timer!!()
        }
        val idle = logs.single { it.contains("video idle") }
        assertTrue(idle.contains("videoGapMs=5000"))
        val starved = logs.single { it.contains("VIDEO STARVED") }
        assertTrue(starved.contains("videoGapMs=10000"))
        assertTrue(starved.contains("audioAlive=true controlAlive=true"))
    }

    @Test fun starvationDoesNotFireWhenAudioIsDead() {
        val watch = watchdog()
        watch.arm(session) { controlAt * 1_000_000 }
        repeat(12) {
            now += 1_000
            controlAt = now
            timer!!()
        }
        assertTrue(logs.any { it.contains("video idle") })
        assertFalse(logs.any { it.contains("VIDEO STARVED") })
    }

    @Test fun starvationDoesNotFireWhenControlIsSilent() {
        val watch = watchdog()
        watch.arm(session) { controlAt * 1_000_000 }
        // Video flows for 61 s with no control traffic: control goes stale while video is healthy.
        repeat(61) {
            now += 1_000
            watch.noteVideoFrame(session, now * 1_000_000)
            watch.noteAudioPacket(session, now * 1_000_000)
            timer!!()
        }
        // Video stops; audio stays alive, control stays silent.
        repeat(12) {
            now += 1_000
            watch.noteAudioPacket(session, now * 1_000_000)
            timer!!()
        }
        assertTrue(logs.any { it.contains("video idle") })
        assertFalse(logs.any { it.contains("VIDEO STARVED") })
    }

    @Test fun idleAndStarvationLogOncePerEpisodeAndResumeClearsThem() {
        val watch = watchdog()
        watch.arm(session) { controlAt * 1_000_000 }
        repeat(12) {
            now += 1_000
            watch.noteAudioPacket(session, now * 1_000_000)
            controlAt = now
            timer!!()
        }
        assertEquals(1, logs.count { it.contains("video idle") })
        assertEquals(1, logs.count { it.contains("VIDEO STARVED") })
        // Video resumes, then stops again: both lines fire a second time.
        now += 33
        watch.noteVideoFrame(session, now * 1_000_000)
        repeat(12) {
            now += 1_000
            watch.noteAudioPacket(session, now * 1_000_000)
            controlAt = now
            timer!!()
        }
        assertEquals(2, logs.count { it.contains("video idle") })
        assertEquals(2, logs.count { it.contains("VIDEO STARVED") })
    }

    @Test fun videoThatNeverStartsTripsFromArmTime() {
        val watch = watchdog()
        watch.arm(session) { controlAt * 1_000_000 }
        repeat(11) {
            now += 1_000
            watch.noteAudioPacket(session, now * 1_000_000)
            controlAt = now
            timer!!()
        }
        assertTrue(logs.any { it.contains("video idle") })
        assertTrue(logs.any { it.contains("VIDEO STARVED") })
    }

    @Test fun notesFromAnotherSessionAreIgnored() {
        val watch = watchdog()
        watch.arm(session) { controlAt * 1_000_000 }
        repeat(12) {
            now += 1_000
            watch.noteVideoFrame(otherSession, now * 1_000_000)
            watch.noteAudioPacket(session, now * 1_000_000)
            controlAt = now
            timer!!()
        }
        assertTrue(logs.any { it.contains("VIDEO STARVED") })
    }

    @Test fun disarmStopsChecksAndStaleDisarmCannotDisarmNewSession() {
        val watch = watchdog()
        watch.arm(session) { controlAt * 1_000_000 }
        assertTrue(watch.disarm(session))
        assertFalse(watch.isArmed)
        assertNull(timer)
        now += 60_000
        assertTrue(logs.none { it.contains("idle") || it.contains("STARVED") })

        watch.arm(session) { controlAt * 1_000_000 }
        assertFalse(watch.disarm(otherSession))
        assertTrue(watch.isArmed)
        assertNotNull(timer)
    }

    @Test fun rearmReplacesPreviousSessionAndResetsLatches() {
        val watch = watchdog()
        watch.arm(session) { controlAt * 1_000_000 }
        repeat(12) {
            now += 1_000
            watch.noteAudioPacket(session, now * 1_000_000)
            controlAt = now
            timer!!()
        }
        assertEquals(1, logs.count { it.contains("VIDEO STARVED") })
        logs.clear()
        watch.arm(otherSession) { controlAt * 1_000_000 }
        repeat(12) {
            now += 1_000
            watch.noteAudioPacket(otherSession, now * 1_000_000)
            controlAt = now
            timer!!()
        }
        assertEquals(1, logs.count { it.contains("VIDEO STARVED") })
    }

    @Test fun naturalGapUnderTwoSecondsNeverTripsIdle() {
        val watch = watchdog()
        watch.arm(session) { controlAt * 1_000_000 }
        // Worst natural gap seen on device: 1944 ms between arrivals.
        repeat(30) {
            now += 1_944
            watch.noteVideoFrame(session, now * 1_000_000)
            watch.noteAudioPacket(session, now * 1_000_000)
            controlAt = now
            timer?.let { tick -> timer = null; tick() }
        }
        assertFalse(logs.any { it.contains("idle") || it.contains("STARVED") })
    }
}
