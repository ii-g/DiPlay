package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AirPlay SET_PARAMETER volume: the iPhone's volume value reaches the listener and is logged,
 * and a binary-plist body still takes the corner-mask path untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AirPlayVolumeSessionTest {
    private val sample = ByteArray(96) { it.toByte() }

    private class RecordingListener : AirPlaySessionListener {
        val volumes = mutableListOf<Float>()
        val applied = mutableListOf<AirPlayVolumeApplied?>()
        val debugLines = mutableListOf<String>()
        val masks = mutableListOf<Pair<ByteArray, Int>>()

        override fun onAirPlayVolume(session: AirPlaySession, volumeDb: Float): AirPlayVolumeApplied? {
            volumes += volumeDb
            val result = AirPlayVolumeApplied(8, 15)
            applied += result
            return result
        }

        override fun onDebugLog(message: String) {
            debugLines += message
        }

        override fun onCornerMask(session: AirPlaySession, png: ByteArray, displayWidth: Int) {
            masks += png to displayWidth
        }
    }

    private fun session(nativeMask: Boolean, listener: RecordingListener): AirPlaySession =
        AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
                main = AirPlayDisplayConfig(1280, 720, mainDisplayCornerMasks = true,
                    useIphoneCornerMask = nativeMask)),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = listener,
            media = object : AirPlayMediaHandler {},
        )

    private fun handle(session: AirPlaySession, body: ByteArray): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(session,
                RtspMessage.Request("SET_PARAMETER", "rtsp://test", "RTSP/1.0", emptyMap(), body))
                as RtspMessage.Response

    @Test
    fun textVolumeBodyReachesListenerAndIsLogged() {
        val listener = RecordingListener()
        val session = session(false, listener)
        try {
            val response = handle(session, "volume: -11.5\r\n".toByteArray())
            assertEquals(200, response.status ?: 200)
            assertEquals(listOf(-11.5f), listener.volumes)
            assertEquals(listOf(AirPlayVolumeApplied(8, 15)), listener.applied)
            assertTrue(listener.debugLines.any { it == "airplay SET_PARAMETER volume=-11.5 applied=8/15" })
        } finally {
            session.close()
        }
    }

    @Test
    fun bodyWithoutVolumeLogsAbsentAndAppliesNothing() {
        val listener = RecordingListener()
        val session = session(false, listener)
        try {
            val response = handle(session, "progress: 0\r\n".toByteArray())
            assertEquals(200, response.status ?: 200)
            assertTrue(listener.volumes.isEmpty())
            assertTrue(listener.debugLines.any { it == "airplay SET_PARAMETER volume=absent applied=none" })
        } finally {
            session.close()
        }
    }

    @Test
    fun bplistBodyStillTakesCornerMaskPathAndLogsAbsent() {
        val listener = RecordingListener()
        val session = session(true, listener)
        try {
            val body = BplistCodec.encode(mapOf("update" to mapOf("topLeftCornerMask" to sample)))
            val response = handle(session, body)
            assertEquals(200, response.status ?: 200)
            assertTrue(listener.volumes.isEmpty())
            assertEquals(1, listener.masks.size)
            assertTrue(listener.debugLines.any { it == "airplay SET_PARAMETER volume=absent applied=none" })
        } finally {
            session.close()
        }
    }
}
