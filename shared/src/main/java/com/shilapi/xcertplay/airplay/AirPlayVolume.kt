package com.shilapi.xcertplay.airplay

/**
 * AirPlay volume (RTSP SET_PARAMETER "volume: <dB>") mapped onto Android's music stream.
 *
 * AirPlay 1 volume is a float in dB: -144 is mute, -30..0 is the usable range. The iPhone is
 * not known to send it during a CarPlay session (captures log "volume no" at audio setup),
 * but no published capture ever pressed the volume buttons mid-session, so this attempt
 * advertises it and logs what arrives: a missing log line means the phone sent nothing.
 */
object AirPlayVolume {
    /** AirPlay mute. */
    const val MUTE_DB = -144f

    /** Bottom of the usable AirPlay volume range. */
    const val MIN_DB = -30f

    /** Top of the usable AirPlay volume range (full volume). */
    const val MAX_DB = 0f

    /** dB to advertise in /info for the current music-stream level. */
    fun dbFromStep(current: Int, max: Int): Float {
        if (max <= 0) return MIN_DB
        val fraction = current.coerceIn(0, max).toFloat() / max
        return MIN_DB + (MAX_DB - MIN_DB) * fraction
    }

    /**
     * Music-stream step for a dB value from the iPhone: -144 (mute) maps to 0, -30..0 maps
     * linearly to 0..max, everything else clamps. Non-finite values map to 0.
     */
    fun stepFromDb(db: Float, max: Int): Int {
        if (max <= 0 || !db.isFinite()) return 0
        if (db <= MUTE_DB) return 0
        val fraction = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
        return Math.round(fraction * max)
    }

    /**
     * Parses a plain-text AirPlay 1 parameter body ("volume: -11.123877") for its volume line.
     * Returns the dB value, or null when the body has no parseable volume line — including a
     * binary-plist body, which simply yields no text line.
     */
    fun parseVolumeDb(body: ByteArray): Float? {
        val text = runCatching { String(body, Charsets.UTF_8) }.getOrNull() ?: return null
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (!line.lowercase().startsWith("volume:")) continue
            val value = line.substringAfter(':').trim().toFloatOrNull() ?: return null
            if (!value.isFinite()) return null
            return value
        }
        return null
    }
}

/** The music-stream step a phone volume value was applied to, with the stream's maximum. */
data class AirPlayVolumeApplied(val step: Int, val max: Int)
