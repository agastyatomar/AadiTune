package com.zionhuang.innertube.models.response

import com.zionhuang.innertube.models.ResponseContext
import com.zionhuang.innertube.models.Thumbnails
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatSelectionTest {

    private fun format(
        itag: Int = 140,
        bitrate: Int = 128_000,
        contentLength: Long? = 2_703_252L,
        url: String? = "https://example.full/audio.webm",
        width: Int? = null,
    ): PlayerResponse.StreamingData.Format = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = url,
        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
        bitrate = bitrate,
        width = width,
        height = null,
        contentLength = contentLength,
        quality = "tiny",
        fps = null,
        qualityLabel = null,
        averageBitrate = bitrate,
        audioQuality = "AUDIO_QUALITY_LOW",
        approxDurationMs = "180000",
        audioSampleRate = 44100,
        audioChannels = 2,
        loudnessDb = null,
        lastModified = null,
    )

    private fun response(
        lengthSeconds: String? = "180",
        formats: List<PlayerResponse.StreamingData.Format>,
    ): PlayerResponse = PlayerResponse(
        responseContext = ResponseContext(visitorData = "", serviceTrackingParams = null),
        playabilityStatus = PlayerResponse.PlayabilityStatus("OK", null),
        playerConfig = null,
        streamingData = PlayerResponse.StreamingData(formats = null, adaptiveFormats = formats, expiresInSeconds = 21_540),
        videoDetails = lengthSeconds?.let {
            PlayerResponse.VideoDetails(
                videoId = "dQw4w9WgXcQ",
                title = "test",
                author = "test",
                channelId = "channel",
                lengthSeconds = it,
                musicVideoType = null,
                viewCount = "1",
                thumbnail = Thumbnails(emptyList()),
            )
        },
    )

    @Test
    fun `full-length real format matches duration`() {
        val format = format(contentLength = 2_703_252L)
        assertTrue(AudioFormatSelection.isFullLength(format, 180) == true)
        assertFalse(AudioFormatSelection.isDefinitelyPreview(format, 180))
    }

    @Test
    fun `30-second sample for 3-minute track is rejected`() {
        // 512KB opus @128kbps ~= 32s of audio
        val sample = format(contentLength = 524_288L)
        assertTrue(AudioFormatSelection.isFullLength(sample, 180) == false)
        assertTrue(AudioFormatSelection.isDefinitelyPreview(sample, 180))
    }

    @Test
    fun `unknown lengths are undecidable`() {
        assertTrue(AudioFormatSelection.isFullLength(format(contentLength = null), 180) == null)
        assertNull(AudioFormatSelection.isFullLength(format(), null))
        assertFalse(AudioFormatSelection.isDefinitelyPreview(format(contentLength = null), 180))
        assertFalse(AudioFormatSelection.isDefinitelyPreview(format(), null))
    }

    @Test
    fun `0-second duration (live) is never considered full length`() {
        val sample = format(contentLength = 524_288L)
        assertTrue(AudioFormatSelection.isFullLength(sample, 0) == null)
        assertFalse(AudioFormatSelection.isDefinitelyPreview(sample, 0))
    }

    @Test
    fun `hasFullLengthAudio accepts complete responses`() {
        val ok = response(formats = listOf(format()))
        assertTrue(AudioFormatSelection.hasFullLengthAudio(ok))
    }

    @Test
    fun `hasFullLengthAudio rejects sample-only responses`() {
        val samplesOnly = response(
            "180",
            listOf(
                format(itag = 140, contentLength = 524_288L),
                format(itag = 251, bitrate = 141_666, contentLength = 573_440L),
            ),
        )
        assertFalse(AudioFormatSelection.hasFullLengthAudio(samplesOnly))
    }

    @Test
    fun `hasFullLengthAudio allows unknown-length candidates but not when everything is a sample`() {
        // Unknown length gives no evidence either way (accepted for playback)
        val unknown = response("180", listOf(format(contentLength = null)))
        assertTrue(AudioFormatSelection.hasFullLengthAudio(unknown))

        // All formats lack url -> no candidates -> false
        val noUrls = response("180", listOf(format(url = null)))
        assertFalse(AudioFormatSelection.hasFullLengthAudio(noUrls))

        // Missing duration -> false
        val noDuration = response(null, listOf(format()))
        assertFalse(AudioFormatSelection.hasFullLengthAudio(noDuration))
    }
}
