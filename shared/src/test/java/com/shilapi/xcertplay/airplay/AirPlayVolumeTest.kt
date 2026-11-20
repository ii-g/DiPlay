package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirPlayVolumeTest {
    @Test
    fun stepFromDbMapsRangeEndsAndMute() {
        assertEquals(0, AirPlayVolume.stepFromDb(-30f, 15))
        assertEquals(15, AirPlayVolume.stepFromDb(0f, 15))
        assertEquals(0, AirPlayVolume.stepFromDb(-144f, 15))
    }

    @Test
    fun stepFromDbMapsMidpointToRoundedHalf() {
        // -15 dB is halfway through -30..0; 7.5 steps rounds to 8.
        assertEquals(8, AirPlayVolume.stepFromDb(-15f, 15))
        assertEquals(11, AirPlayVolume.stepFromDb(-7.5f, 15))
        assertEquals(9, AirPlayVolume.stepFromDb(-11.123877f, 15))
    }

    @Test
    fun stepFromDbClampsOutOfRangeAndDegenerateInput() {
        assertEquals(0, AirPlayVolume.stepFromDb(-200f, 15))
        assertEquals(15, AirPlayVolume.stepFromDb(5f, 15))
        assertEquals(0, AirPlayVolume.stepFromDb(Float.NaN, 15))
        assertEquals(0, AirPlayVolume.stepFromDb(0f, 0))
    }

    @Test
    fun dbFromStepMapsRangeEnds() {
        assertEquals(-30f, AirPlayVolume.dbFromStep(0, 15), 0.001f)
        assertEquals(0f, AirPlayVolume.dbFromStep(15, 15), 0.001f)
        assertEquals(-14f, AirPlayVolume.dbFromStep(8, 15), 0.001f)
        assertEquals(-30f, AirPlayVolume.dbFromStep(0, 0), 0.001f)
    }

    @Test
    fun parseVolumeDbReadsPlainTextBody() {
        assertEquals(-11.123877f, AirPlayVolume.parseVolumeDb("volume: -11.123877\r\n".toByteArray())!!, 0.001f)
        assertEquals(-144f, AirPlayVolume.parseVolumeDb("volume: -144\n".toByteArray())!!, 0.001f)
        assertEquals(-5f, AirPlayVolume.parseVolumeDb("volume:-5\n".toByteArray())!!, 0.001f)
        assertEquals(-20f, AirPlayVolume.parseVolumeDb("Volume: -20\n".toByteArray())!!, 0.001f)
        assertEquals(-3.5f, AirPlayVolume.parseVolumeDb("progress: 0\nvolume: -3.5\n".toByteArray())!!, 0.001f)
    }

    @Test
    fun parseVolumeDbReturnsNullWithoutAParseableVolumeLine() {
        assertNull(AirPlayVolume.parseVolumeDb("".toByteArray()))
        assertNull(AirPlayVolume.parseVolumeDb("progress: 0\n".toByteArray()))
        assertNull(AirPlayVolume.parseVolumeDb("volume: nope\n".toByteArray()))
        assertNull(AirPlayVolume.parseVolumeDb("volume: NaN\n".toByteArray()))
        // A binary-plist body has no text volume line and must not throw.
        assertNull(AirPlayVolume.parseVolumeDb(byteArrayOf(0x62, 0x70, 0x6c, 0x69, 0x73, 0x74, 0x30, 0x30)))
    }
}
