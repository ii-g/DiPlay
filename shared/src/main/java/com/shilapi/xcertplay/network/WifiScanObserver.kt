package com.shilapi.xcertplay.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager

/**
 * Passively watches Wi-Fi scans and the radio while an AirPlay session is active, so the
 * video gap log can say whether a scan ran near a stall.
 *
 * Runs for every wireless session (P2P and MANUAL alike) because it is started from the
 * session callbacks, not from either hotspot backend. It changes nothing: it never starts
 * a scan, never touches the radio, and only notes when the system reports scan results.
 * On this head unit the framework's automatic scans cannot be paused (no ADB), so
 * observing is all that is available.
 */
internal class WifiScanObserver(
    context: Context,
    private val log: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val wifiManager: WifiManager? =
        appContext.getSystemService(WifiManager::class.java)
    private val snapshotProvider = VideoGapRadioState.Snapshot { snapshot() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            WifiScanEvents.onScanCompleted()
        }
    }

    @Volatile private var started = false

    /** Idempotent: a replacement session re-activating must not register twice. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        VideoGapRadioState.provider = snapshotProvider
        runCatching {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        }.onFailure { log("Wi-Fi scan observer registration failed: ${it.message}") }
    }

    @Synchronized
    fun close() {
        if (!started) return
        started = false
        runCatching { appContext.unregisterReceiver(receiver) }
        if (VideoGapRadioState.provider === snapshotProvider) VideoGapRadioState.provider = null
    }

    /** WifiManager reads are best-effort: SSID needs location permission and may be hidden. */
    private fun snapshot(): String {
        val wifi = wifiManager ?: return "radio=wifiManager=unavailable"
        val enabled = runCatching { wifi.isWifiEnabled }.getOrNull()
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val freqMHz = info?.frequency?.takeIf { it > 0 } ?: "unknown"
        val ssid = info?.ssid?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: "unknown"
        return "radio=wifiEnabled=$enabled freqMHz=$freqMHz ssid=$ssid"
    }
}
