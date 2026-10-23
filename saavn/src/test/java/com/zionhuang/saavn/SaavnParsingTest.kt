package com.zionhuang.saavn

import com.zionhuang.saavn.models.SaavnSearchResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaavnParsingTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `parse search response and unknown fields`() {
        val text = javaClass.classLoader!!.getResource("saavn_search_fixture.json")!!.readText()
        val response = json.decodeFromString<SaavnSearchResponse>(text)
        val tracks = response.data!!.results
        assertEquals(2, tracks.size)
        assertEquals("9tFRKr1K", tracks[0].id)
        assertEquals("Synth Sunrise", tracks[0].name)
    }

    @Test
    fun `duration seconds from string and best quality selection`() {
        val text = javaClass.classLoader!!.getResource("saavn_search_fixture.json")!!.readText()
        val tracks = json.decodeFromString<SaavnSearchResponse>(text).data!!.results
        assertEquals(214, tracks[0].durationSeconds())
        assertEquals(45, tracks[1].durationSeconds())
        assertTrue(tracks[0].bestStreamUrl()!!.contains("320"))
        // Second track only has 128kbps = fallback selection
        assertTrue(tracks[1].bestStreamUrl()!!.contains("128"))
    }

    @Test
    fun `artwork and explicit behaviors`() {
        val text = javaClass.classLoader!!.getResource("saavn_search_fixture.json")!!.readText()
        val tracks = json.decodeFromString<SaavnSearchResponse>(text).data!!.results
        assertTrue(tracks[0].bestThumbUrl()!!.contains("a500"))
        assertTrue(tracks[1].isExplicit())
        assertFalse(tracks[0].isExplicit())
    }
}
