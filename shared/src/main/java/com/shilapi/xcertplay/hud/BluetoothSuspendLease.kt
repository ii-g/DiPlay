package com.shilapi.xcertplay.hud

/** A durable promise to restore a radio observed ON before our disable command. */
internal interface BluetoothRestoreJournal {
    fun pending(): Boolean
    fun write(pending: Boolean): Boolean
}

/** Cancellation stays fast even when an already-issued radio command is blocked. */
internal class BluetoothSuspendLease(
    private val journal: BluetoothRestoreJournal,
    private val readEnabled: () -> Boolean?,
    private val requestEnabled: (Boolean) -> Unit,
    private val awaitEnabled: (Boolean) -> Boolean,
) {
    private val stateLock = Any()
    private val commandLock = Any()
    private var owner: Any? = null
    private var generation = 0L

    fun begin(requester: Any): Long? = synchronized(stateLock) {
        if (owner === requester) return null
        owner = requester
        ++generation
    }

    fun end(requester: Any): Long? = synchronized(stateLock) {
        if (owner !== requester) return null
        owner = null
        ++generation
    }

    /** Opening settings during a live session must not release that session's lease. */
    fun recoveryOnAppOpen(): Long? = synchronized(stateLock) {
        if (owner != null) return null
        // Non-owning cleanup has the same recovery goal as an already queued handshake.
        // Reuse its ticket so a late old-controller close cannot make that handshake fail.
        generation
    }

    /** A new wireless handshake also retires an older pending disable. */
    fun beforeHandshake(): Long = synchronized(stateLock) {
        owner = null
        ++generation
    }

    fun isSuspended(): Boolean = pending() == true && enabled() == false

    /** For the report: the radio state the pause starts from, and whether [ticket] still owns it. */
    fun radioEnabled(): Boolean? = enabled()
    fun isCurrent(ticket: Long): Boolean = current(ticket)

    fun suspend(ticket: Long): Boolean = synchronized(commandLock) {
        if (!current(ticket)) return false
        // Reconcile an interrupted older lease before observing this session's original state.
        if (!restoreOwned()) return false
        if (!current(ticket) || enabled() != true) return false
        // commit(), not apply(): process death after the command must leave recovery evidence.
        if (!runCatching { journal.write(true) }.getOrDefault(false)) return false
        if (!current(ticket)) {
            restoreOwned()
            return false
        }
        runCatching { requestEnabled(false) }
        val disabled = runCatching { awaitEnabled(false) }.getOrDefault(false)
        if (!disabled || !current(ticket)) {
            // A cancelled command may already have executed. Compensate while still serialized.
            restoreOwned()
            return false
        }
        true
    }

    fun restore(ticket: Long): Boolean = synchronized(commandLock) {
        if (!currentRecovery(ticket)) return false
        restoreOwned()
    }

    private fun current(ticket: Long): Boolean = synchronized(stateLock) {
        generation == ticket && owner != null
    }

    private fun currentRecovery(ticket: Long): Boolean = synchronized(stateLock) {
        generation == ticket && owner == null
    }

    private fun pending(): Boolean? = runCatching { journal.pending() }.getOrNull()
    private fun enabled(): Boolean? = runCatching { readEnabled() }.getOrNull()

    private fun restoreOwned(): Boolean {
        val owned = pending() ?: return false
        if (!owned) return true
        when (enabled()) {
            true -> Unit // It never turned off, or somebody already restored the original state.
            false -> {
                runCatching { requestEnabled(true) }
                if (!runCatching { awaitEnabled(true) }.getOrDefault(false)) return false
            }
            null -> return false // Unknown state is not permission to enable a user's radio.
        }
        return runCatching { journal.write(false) }.getOrDefault(false)
    }
}

/** One report line for each pause attempt, so a report shows why calls still ring on the car (#18). */
internal object BluetoothPauseOutcome {
    fun describe(
        paused: Boolean,
        radioBefore: Boolean?,
        stillCurrent: Boolean,
        adbAccess: String?,
        path: BluetoothRadioRequest.Path? = null,
    ): String = when {
        paused -> "car Bluetooth paused: Android Bluetooth is off until CarPlay ends"
        !stillCurrent -> "car Bluetooth pause cancelled: CarPlay ended first"
        radioBefore == false -> "car Bluetooth pause skipped: Android Bluetooth was already off"
        radioBefore == null -> "car Bluetooth pause skipped: Android Bluetooth state unknown"
        path == BluetoothRadioRequest.Path.ADAPTER_NOT_ALLOWED ->
            "car Bluetooth pause failed: ADB access ${adbAccess ?: "unknown"}; app API not available on this Android"
        path == BluetoothRadioRequest.Path.ADAPTER_REFUSED ->
            "car Bluetooth pause failed: ADB access ${adbAccess ?: "unknown"}; app API refused"
        path == BluetoothRadioRequest.Path.ADAPTER_SENT ->
            "car Bluetooth pause failed: Android Bluetooth stayed on (app API)"
        else -> "car Bluetooth pause failed: Android Bluetooth stayed on"
    }
}
/**
 * What the app API did when svc gave no ADB reply; null when the shell answered.
 * (Trial build: only the app-API line is ported here; the full evidence object
 * lives on #1287's structure, which this tree does not have.)
 */
object BluetoothPauseEvidence {
    private const val PREFIX = "car Bluetooth pause evidence:"

    /** What the app API did when svc gave no ADB reply; null when the shell answered. */
    internal fun appApiLine(path: BluetoothRadioRequest.Path?): String? = when (path) {
        BluetoothRadioRequest.Path.ADAPTER_SENT -> "$PREFIX app API sent the Bluetooth disable"
        BluetoothRadioRequest.Path.ADAPTER_REFUSED -> "$PREFIX app API refused the Bluetooth disable"
        BluetoothRadioRequest.Path.ADAPTER_NOT_ALLOWED -> "$PREFIX app API cannot send the Bluetooth disable on this Android"
        else -> null
    }
}
