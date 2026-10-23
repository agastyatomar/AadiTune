package com.zionhuang.innertube.models.response

/**
 * Pure, unit-testable selection logic for audio formats.
 *
 * Guards against the "song stops at ~30 seconds" family of failures by
 * verifying that a format's byte-length is compatible with the track's
 * advertised duration, and by preferring such formats in resolution.
 */
object AudioFormatSelection {

    /**
     * Is [format]'s content length plausible for a FULL-length track of
     * [durationSeconds] at the format's peak byte-rate estimate
     * expectedBytes = durationSeconds * format.bitrate / 8
     *
     * - within 0.5x..3x of expected: `true`
     * - A 30s preview for a 3-minute track is ~1/6 of expected → `false`
     */
    fun isFullLength(
        format: PlayerResponse.StreamingData.Format,
        durationSeconds: Long?,
    ): Boolean? {
        val contentLength = format.contentLength ?: return null
        if (durationSeconds == null || durationSeconds <= 0 || contentLength <= 0) return null
        val expectedBytes = durationSeconds * format.bitrate / 8
        if (expectedBytes <= 0) return null
        return contentLength >= expectedBytes / 2 && contentLength <= expectedBytes * 3
    }

    /**
     * Would this format clearly only provide a short preview of the track
     * (content-length well under a third of the full-size expectation)?
     */
    fun isDefinitelyPreview(
        format: PlayerResponse.StreamingData.Format,
        durationSeconds: Long?,
    ): Boolean {
        if (durationSeconds == null || durationSeconds <= 0) return false
        val contentLength = format.contentLength ?: return false
        val expectedBytes = durationSeconds * format.bitrate / 8
        if (expectedBytes <= 0) return false
        // Content-length well under a third of the expected full-size bytes.
        return contentLength * 3 < expectedBytes
    }

    /**
     * Does [playerResponse] contain at least one audio format that is full-length?
     * Returns `true` when plausibly complete, `false` when provably truncated
     * (e.g. a client served 30-second samples for a full-length track).
     * Unknown data (missing durations/lengths) counts as neither.
     */
    fun hasFullLengthAudio(playerResponse: PlayerResponse?): Boolean {
        if (playerResponse == null) return false
        val duration = playerResponse.videoDetails?.lengthSeconds?.toLongOrNull()
        val candidates = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.url != null }
            .orEmpty()
        if (duration == null || duration <= 0 || candidates.isEmpty()) return false
        val unknownLength = candidates.any { it.contentLength == null }
        val anyFullLength = candidates.any { isFullLength(it, duration) == true }
        val anyPreview = candidates.all { isDefinitelyPreview(it, duration) }
        return (anyFullLength || unknownLength) && !anyPreview
    }
}
