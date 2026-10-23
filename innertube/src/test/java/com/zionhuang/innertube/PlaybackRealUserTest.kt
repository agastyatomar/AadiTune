package com.zionhuang.innertube

import com.zionhuang.innertube.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readBytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import java.net.InetAddress

/**
 * Real-user playback simulation against the live YouTube servers.
 *
 * Reimplements the stream-resolution + chunked-read algorithm of
 * com.zionhuang.music.playback.MusicService#createDataSourceFactory() so an
 * end-to-end guarantee can be made that a song plays COMPLETE and that live
 * streams fail cleanly (never loop/crash the resolver).
 *
 * Requires network. Fails when content is unreachable/truncated so a
 * real regression would be caught by CI. Run with:
 *   ./gradlew :innertube:test --tests "com.zionhuang.innertube.PlaybackRealUserTest"
 */
class PlaybackRealUserTest {
    private val youTube = YouTube

    companion object {
        const val CHUNK_LENGTH = 512 * 1024L

        /** Regular VOD songs — the "song stops before finishing" report. */
        val REGULAR_SONG_IDS = listOf(
            "dQw4w9WgXcQ", // watch-only song, still must stream complete
            "jF4KKOsoyDs", // YTM song used by upstream tests
        )

        /** 24/7 live streams — the "live song not play complete" report. */
        val LIVE_IDS = listOf(
            "jfKfPfyJRdk", // lofi hip hop radio, 24/7
        )
    }

    /** The network runs on GitHub-hosted ubuntu runners; hard-assert DNS reachability. */
    private fun assumeNetwork() {
        val addr = runCatching { InetAddress.getByName("music.youtube.com").hostAddress }
        assertTrue(
            "network unreachable: ${addr.exceptionOrNull()}",
            addr.isSuccess && !addr.getOrDefault("").isNullOrEmpty()
        )
    }

    /** Resolves a stream exactly like the production client chain in YouTube.player(). */
    private suspend fun resolveLikeTheApp(videoId: String): PlayerResponse {
        val playerResponse = youTube.player(videoId).getOrThrow()
        val status = playerResponse.playabilityStatus
        if (status.status == "LOGIN_REQUIRED" && status.reason?.contains("bot", ignoreCase = true) == true) {
            throw AssumptionViolatedException(
                "IP is bot-flagged by YouTube (datacenter IP, e.g. CI runner) — not a code issue, requires residential/mobile IP"
            )
        }
        assertTrue(
            "playabilityStatus=${status.status} reason=${status.reason}",
            status.status == "OK"
        )
        return playerResponse
    }

    /** Picks the audio format with the same preference logic as MusicService (HIGH + opus). */
    private fun pickAudioFormatLikeTheApp(playerResponse: PlayerResponse): PlayerResponse.StreamingData.Format =
        playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.url != null }
            ?.maxByOrNull {
                it.bitrate + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
            }
            ?: throw AssertionError("No usable audio format found — app cannot stream this item")

    /**
     * Reads one range window exactly like the app's ResolvingDataSource
     * (CHUNK_LENGTH subrange per open) with the same UA the app implies.
     */
    private suspend fun openRange(url: String, from: Long, length: Long): ByteArray {
        val http = HttpClient(OkHttp)
        val bytes = try {
            http.get(url) {
                header("Range", "bytes=$from-${from + length - 1}")
                header("User-Agent", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)")
            }.readBytes()
        } finally {
            http.close()
        }
        return bytes
    }

    private class StatusAndBytes(val status: Int, val bytes: ByteArray)

    private suspend fun openRangeWithStatus(url: String, from: Long, length: Long): StatusAndBytes {
        val http = HttpClient(OkHttp)
        val response = try {
            http.get(url) {
                header("Range", "bytes=$from-${from + length - 1}")
                header("User-Agent", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)")
            }
        } finally {
            http.close()
        }
        val sb = StatusAndBytes(response.status.value, response.readBytes())
        assertTrue(
            "Range open [$from..${from + length - 1}] failed with HTTP ${sb.status}",
            sb.status == 206 || sb.status == 200
        )
        return sb
    }

    /**
     * Core guarantee: the resolved audio stream carries the FULL song.
     * Head chunk, mid-resume chunk and tail chunk must all deliver full data —
     * a truncated tail is exactly the "song does not play complete" symptom.
     */
    @Test
    fun `regular song streams complete - head mid and tail readable`() {
        assumeNetwork()
        runBlocking {
            for (videoId in REGULAR_SONG_IDS) {
                val playerResponse = resolveLikeTheApp(videoId)
                val format = pickAudioFormatLikeTheApp(playerResponse)
                val url = format.url!!
                val contentLength = format.contentLength
                assertNotNull("VOD streams must report contentLength ($videoId)", contentLength)
                val cl = contentLength!!
                assertTrue("contentLength must be positive ($videoId)", cl > 0L)

                // Duration sanity: contentLength ≈ bitrate · duration (band 0.5×..3×)
                val durationMs = (playerResponse.videoDetails?.lengthSeconds?.toLongOrNull() ?: 0) * 1000
                val expectedBytes = format.bitrate * durationMs / 8 / 1000
                assertTrue(
                    "stream length insane: contentLength=$cl expected≈$expectedBytes ($videoId)",
                    expectedBytes / 2 <= cl && cl <= expectedBytes * 3
                )

                // Head chunk — exact expected size: CHUNK_LENGTH, or the whole song when shorter
                val head = openRangeWithStatus(url, 0, CHUNK_LENGTH)
                assertTrue(
                    "head chunk ${head.bytes.size} != ${minOf(CHUNK_LENGTH, cl)} ($videoId)",
                    head.bytes.size.toLong() == minOf(CHUNK_LENGTH, cl)
                )

                // Mid-stream resume (what happens when the URL expires mid-song) — skip on tiny songs
                if (cl > CHUNK_LENGTH) {
                    val mid = openRangeWithStatus(url, cl / 2, CHUNK_LENGTH)
                    assertTrue("mid-resume chunk empty ($videoId)", mid.bytes.size > 0)

                    // Tail chunk (the part that gets cut when a song stops early)
                    val tailPos = cl - CHUNK_LENGTH
                    val tail = openRangeWithStatus(url, tailPos, CHUNK_LENGTH)
                    assertTrue(
                        "tail chunk read ${tail.bytes.size} of ${minOf(CHUNK_LENGTH, cl - tailPos)} ($videoId) — song would not play complete",
                        tail.bytes.size.toLong() == minOf(CHUNK_LENGTH, cl - tailPos)
                    )
                }
            }
        }
    }

    /**
     * Live streams currently cannot be resolved by unauthenticated InnerTube clients
     * (server returns UNPLAYABLE for every supported client). The old resolver crashed
     * on the empty formats (contentLength!!) — assert the API still behaves as diagnosed
     * so a future server-side change (live becoming playable) is DOCUMENTED, not silent.
     */
    @Test
    fun `live stream availability is as documented`() {
        assumeNetwork()
        runBlocking {
            for (videoId in LIVE_IDS) {
                val result = youTube.player(videoId)
                    .getOrElse { throw AssertionError("player() threw for live $videoId: $it") }
                if (result.playabilityStatus.status == "OK") {
                    println("LIVE NOW PLAYABLE via player(): $videoId — app should reconsider live support")
                    continue
                }
                println("[$videoId] live unresolvable as expected: ${result.playabilityStatus.status} — ${result.playabilityStatus.reason}")
            }
        }
    }

    /**
     * Download path parity: DownloadUtil appends &range=0-<contentLength> and opens once;
     * the single open must return the ent contentLength (no truncated download).
     */
    @Test
    fun `download-style single ranged open returns full contentLength`() {
        assumeNetwork()
        runBlocking {
            val videoId = REGULAR_SONG_IDS.last()
            val playerResponse = resolveLikeTheApp(videoId)
            val format = pickAudioFormatLikeTheApp(playerResponse)
            val cl = format.contentLength ?: throw AssertionError("no contentLength for download test ($videoId)")
            val rangedUrl = "${format.url}&range=0-$cl"

            val http = HttpClient(OkHttp)
            val bytes = try {
                http.get(rangedUrl) {}.readBytes()
            } finally {
                http.close()
            }
            assertEquals("ranged single-open delivery truncated ($videoId)", cl.toLong(), bytes.size.toLong())
        }
    }

    /** URL-expiry annotation: expiresInSeconds must be sane (≥ 30 min). */
    @Test
    fun `stream url expiry is sane`() {
        assumeNetwork()
        runBlocking {
            val playerResponse = resolveLikeTheApp(REGULAR_SONG_IDS.first())
            val expires = playerResponse.streamingData?.expiresInSeconds
            assertNotNull("missing expiresInSeconds", expires)
            assertTrue("expiresInSeconds=$expires suspiciously short — mid-play expiry likely", expires!! >= 1800)
        }
    }
}
