package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Starts the watcher for other apps' calls when that setting is on, even without opening DiPlay.
        PhoneAudioRoute.attach(context)
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        if (!launchEnabled) return

        try {
            context.startActivity(bootLaunchIntent(context))
            StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            Log.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
        }
        // The activity launch alone is not proof: on some head-unit firmware the call returns
        // while the system never shows the app. The foreground service stays alive to watch for
        // the activity and retry the launch when it stays hidden.
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DiPlaySessionService::class.java)
                    .setAction(DiPlaySessionService.ACTION_BOOT),
            )
            StartupDiagnosticSnapshot.serviceResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.serviceResult(context, error)
            Log.w(TAG, "Boot auto-start could not start the session service", error)
        }
    }

    companion object {
        const val TAG = "xcertplay-boot"

        /**
         * Shaped like the launcher icon's intent: a USB authenticator or iPhone plugged in can open CarPlay
         * before this broadcast, and DiPlayActivity then returns to that session instead of showing Home over it.
         */
        fun bootLaunchIntent(context: Context): Intent =
            Intent(context, DiPlayActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
    }
}
