package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    private val bootHandler = Handler(Looper.getMainLooper())
    private var bootWatchScheduled = false
    private var bootRetriesMade = 0

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay connection", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else {
            // Android 7.x has no notification channels; the priority stands in for the channel importance.
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        val open = PendingIntent.getActivity(this, 0, CarPlayScreen.intent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = builder
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("DiPlay")
            .setContentText("CarPlay connection running")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // Without it Android stops location updates while another car app (the reversing camera,
            // the car's own map) covers CarPlay, and the iPhone gets no position until DiPlay is back.
            if (AirPlayPersistence.loadLocationReportingEnabled(this) &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, notification, types)
        } else startForeground(1, notification)
        if (intent?.action == ACTION_BOOT) scheduleBootRetryWatch()
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // BYD's recents force-stops the package ~10 ms after removing the task: end guidance first.
        com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
        CarPlayBackgroundSession.stop()
        stopSelf()
    }
    override fun onDestroy() {
        bootHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Watches the boot auto-start from the foreground service: the BOOT_COMPLETED launch can
     * return without the firmware ever showing the app, so this re-checks after a delay and
     * relaunches DiPlayActivity while it stays hidden, at most [BootLaunchRetry.MAX_RETRIES]
     * times. When the activity appears, or the retries run out, the watch ends; the service
     * then stops itself unless a session started meanwhile (the normal session path owns the
     * service from then on).
     */
    private fun scheduleBootRetryWatch() {
        if (bootWatchScheduled) return
        bootWatchScheduled = true
        bootRetriesMade = 0
        BootLaunchRetry.delayForRetry(0)?.let { delay ->
            bootHandler.postDelayed({ runBootRetryCheck() }, delay)
        }
    }

    private fun runBootRetryCheck() {
        val seenAt = StartupDiagnosticSnapshot.activitySeenAt(this)
        if (!BootLaunchRetry.shouldRetry(seenAt, bootRetriesMade)) {
            finishBootWatch()
            return
        }
        val attempt = bootRetriesMade + 1
        try {
            startActivity(BootReceiver.bootLaunchIntent(this))
            StartupDiagnosticSnapshot.retryAttempt(this, attempt)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.retryAttempt(this, attempt, error)
            Log.w(TAG, "Boot retry $attempt could not launch DiPlayActivity", error)
        }
        bootRetriesMade = attempt
        BootLaunchRetry.delayForRetry(bootRetriesMade)?.let { delay ->
            bootHandler.postDelayed({ runBootRetryCheck() }, delay)
        } ?: finishBootWatch()
    }

    private fun finishBootWatch() {
        bootHandler.removeCallbacksAndMessages(null)
        if (!CarPlayBackgroundSession.hasSession()) stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        const val ACTION_BOOT = "com.shihab.diplay.BOOT_START"
        private const val CHANNEL = "diplay_connection"
        private const val TAG = "xcertplay-boot"
    }
}
