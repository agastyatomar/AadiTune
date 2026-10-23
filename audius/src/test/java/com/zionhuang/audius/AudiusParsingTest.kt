package com.zionhuang.audius

import com.zionhuang.audius.models.AudiusTrack
import com.zionhuang.audius.models.AudiusTracksResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiusParsingTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `parse fixture data envelope and ignore unknown fields`() {
        val text = javaClass.classLoader!!.getResource("audius_search_fixture.json")!!.readText()
        val response = json.decodeFromString<AudiusTracksResponse>(text)
        assertEquals(2, response.data.size)
        assertEquals("V4uW99", response.data[0].id)
        assertEquals("Synth Sunrise", response.data[0].title)
    }

    @Test
    fun `playback_seconds takes precedence and legacy duration falls back to seconds`() {
        val text = javaClass.classLoader!!.getResource("audius_search_fixture.json")!!.readText()
        val response = json.decodeFromString<AudiusTracksResponse>(text)
        assertEquals(214, response.data[0].durationSeconds())
        // 45000 ms -> 45 s for old nominators
        assertEquals(45, response.data[1].durationSeconds())
        assertNull(response.data[1].playbackSeconds)
    }

    @Test
    fun `artist name is sourced from nested user`() {
        val text = javaClass.classLoader!!.getResource("audius_search_fixture.json")!!.readText()
        val tracks = json.decodeFromString<AudiusTracksResponse>(text).data
        assertEquals("NovaWave", tracks[0].user?.name)
        assertEquals("Unknown Artist", tracks[1].user?.name)
    }

    @Test
    fun `artwork prefers medium square`() {
        val text = javaClass.classLoader!!.getResource("audius_search_fixture.json")!!.readText()
        val tracks = json.decodeFromString<AudiusTracksResponse>(text).data
        assertEquals("https://host.example/a480.jpg", tracks[0].artwork?.bestUrl())
        assertFalse(tracks[0].artwork!!.bestUrl()!!.contains("150"))
        assertNull(tracks[1].artwork)
    }

    @Test
    fun `stream app_name attribution and host discovery contract`() {
        assertEquals("NovaTune", Audius.APP_NAME)
        assertTrue(Audius.HOST_FALLBACK.startsWith("https://"))
    }
}
