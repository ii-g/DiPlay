package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Looper
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiPlaySessionServiceBootTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var controller: ServiceController<DiPlaySessionService>? = null

    @Before fun reset() {
        app.getSharedPreferences("diplay_startup_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        StartupDiagnosticSnapshot.received(app, true)
    }

    @After fun tearDown() {
        controller?.destroy()
        controller = null
    }

    private fun bootIntent() = Intent(app, DiPlaySessionService::class.java)
        .setAction(DiPlaySessionService.ACTION_BOOT)

    private fun startBootService() {
        controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        controller!!.get().onStartCommand(bootIntent(), 0, 1)
    }

    /** Drains the started-activity queue; nextStartedActivity pops, so it is read until null. */
    private fun launchedActivities(): List<String> {
        val found = mutableListOf<String>()
        var next = shadowOf(app).nextStartedActivity
        while (next != null) {
            found += next.component!!.className
            next = shadowOf(app).nextStartedActivity
        }
        return found
    }

    @Test fun retriesTwiceWhileTheActivityStaysHiddenThenStops() {
        startBootService()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(listOf(DiPlayActivity::class.java.name), launchedActivities())
        assertEquals(1, StartupDiagnosticSnapshot.retryCount(app))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(listOf(DiPlayActivity::class.java.name), launchedActivities())
        assertEquals(2, StartupDiagnosticSnapshot.retryCount(app))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertTrue(launchedActivities().isEmpty())
        val report = StartupDiagnosticSnapshot.report(app)
        assertTrue(report.contains("retries=1@"))
        assertTrue(report.contains(";2@"))
    }

    @Test fun noRetryOnceTheActivityHasBeenSeen() {
        startBootService()
        StartupDiagnosticSnapshot.markActivitySeen(app)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        assertTrue(launchedActivities().isEmpty())
        assertEquals(0, StartupDiagnosticSnapshot.retryCount(app))
    }

    @Test fun aNormalStartDoesNotWatchTheBootLaunch() {
        controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        controller!!.get().onStartCommand(Intent(app, DiPlaySessionService::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        assertTrue(launchedActivities().isEmpty())
        assertEquals(0, StartupDiagnosticSnapshot.retryCount(app))
    }
}
