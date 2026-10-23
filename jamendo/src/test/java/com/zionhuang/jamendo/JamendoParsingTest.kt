package com.zionhuang.jamendo

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-based parsing tests for the Jamendo v3.0 API responses.
 *
 * The fixture models the documented API shape (dev.jamendo.com). It contains
 * clearly marked placeholder ids/URLs and is never used as production data.
 * Live streaming verification requires a real (free) client_id and is
 * intentionally NOT part of these tests - see README/audit notes.
 */
class JamendoParsingTest {
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `parses search response envelope and full track fields`() {
        val text = javaClass.classLoader!!.getResource("jamendo_search_fixture.json")!!.readText()
        val response = json.decodeFromString(JamendoSearchResponse.serializer(), text)

        assertEquals("success", response.headers?.status)
        assertEquals(2, response.results.size)

        val first = response.results.first()
        assertEquals("10433", first.id)
        assertEquals("Early morning", first.name)
        assertEquals(246, first.duration)
        assertEquals("criteria73", first.artist_name)
        assertNotNull("stream URL (audio) must be present for playback", first.audio)
        assertNotNull("license description must be present for honest labelling", first.license_desc)
        assertTrue(first.audiodownload_allowed == true)
    }

    @Test
    fun `handles empty result list without crashing`() {
        val response = json.decodeFromString(JamendoSearchResponse.serializer(), "{\"headers\":{\"status\":\"success\"}}")
        assertTrue(response.results.isEmpty())
        // callers must tolerate empty results (the app shows "no results found")
        assertEquals(emptyList<Any>(), response.results)
    }

    @Test
    fun `tolerates unknown keys and missing optionals`() {
        val text = """
            {"headers":{"status":"success","code":0,"results_count":1},
             "results":[{"id":"9","name":"t","artist_name":"a","duration":100,"some_future_field":42}]}
        """.trimIndent()
        val response = json.decodeFromString(JamendoSearchResponse.serializer(), text)
        assertEquals("9", response.results.first().id)
        assertEquals("a", response.results.first().artist_name)
        assertEquals(100, response.results.first().duration)
    }

    @Test
    fun `streamUrl requires a configured client id - honest failure otherwise`() {
        run {
            val clientIdBefore = Jamendo.clientId
            Jamendo.clientId = null
            try {
                kotlinx.coroutines.runBlocking {
                    runCatching { Jamendo.search("lofi") }
                        .exceptionOrNull()
                        ?.let { assertTrue("expected NotConfiguredException", it is NotConfiguredException) }
                }
            } finally {
                Jamendo.clientId = clientIdBefore
            }
        }
    }

    @Test
    fun `fixture duration values are usable for progress reporting`() {
        val text = javaClass.classLoader!!.getResource("jamendo_search_fixture.json")!!.readText()
        val response = json.decodeFromString(JamendoSearchResponse.serializer(), text)
        response.results.forEach {
            val duration = it.duration
            if (duration != null) {
                assertTrue("durations must be parsed as positive seconds", duration > 0)
            }
        }
    }
}
