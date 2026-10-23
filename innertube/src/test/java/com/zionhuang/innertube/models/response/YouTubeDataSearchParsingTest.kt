package com.zionhuang.innertube.models.response

import com.zionhuang.innertube.isoDurationToSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-based parsing tests for the official YouTube Data API v3 search
 * response (https://developers.google.com/youtube/v3/docs/search).
 * The fixture mirrors the documented JSON shape; no network is involved.
 */
class YouTubeDataSearchParsingTest {
    private val json = kotlinx.serialization.json.Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `parses successful search response with permitted metadata only`() {
        val text = javaClass.classLoader!!.getResource("youtube_search_fixture.json")!!.readText()
        val response = json.decodeFromString(YouTubeDataSearchResponse.serializer(), text)

        assertEquals(2, response.items.size)
        assertEquals("CAUQAA", response.nextPageToken)

        val first = response.items.first()
        assertEquals("dQw4w9WgXcQ", first.id!!.videoId)
        assertEquals("Test Song Title", first.snippet!!.title)
        assertEquals("Test Channel", first.snippet!!.channelTitle)
        assertEquals("PT3M33S", first.contentDetails!!.duration)
    }

    @Test
    fun `iso-8601 duration parsing is exact or honest-null`() {
        assertEquals(213, "PT3M33S".isoDurationToSeconds())
        assertEquals(3723, "PT1H2M3S".isoDurationToSeconds())
        assertEquals(60, "PT1M".isoDurationToSeconds())
        assertNull("PT0S".isoDurationToSeconds()) // premieres/zero-length stay unknown
        assertNull("P0D".isoDurationToSeconds())
        assertNull("not-a-duration".isoDurationToSeconds())
    }

    @Test
    fun `quota error envelope is surfaced with an actionable message`() {
        val text = javaClass.classLoader!!.getResource("youtube_search_quota_error_fixture.json")!!.readText()
        val response = json.decodeFromString(YouTubeDataSearchResponse.serializer(), text)
        val error = response.error!!
        assertEquals("quotaExceeded", error.errors.first().reason)
        assertEquals(403, error.code)
    }

    @Test
    fun `empty result list is tolerated`() {
        val response = json.decodeFromString(
            YouTubeDataSearchResponse.serializer(),
            "{\"items\":[],\"pageInfo\":{\"totalResults\":0}}",
        )
        assertTrue(response.items.isEmpty())
        assertNull(response.nextPageToken)
    }
}
