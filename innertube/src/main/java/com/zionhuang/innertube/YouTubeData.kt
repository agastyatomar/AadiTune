package com.zionhuang.innertube

import com.zionhuang.innertube.encoder.brotli
import com.zionhuang.innertube.models.response.YouTubeDataErrorEnvelope
import com.zionhuang.innertube.models.response.YouTubeDataSearchResponse
import com.zionhuang.innertube.models.response.YouTubeDataThumbnails
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Official (free-tier) YouTube Data API v3 search client.
 * https://developers.google.com/youtube/v3
 *
 * Limits surfaced honestly (never hidden):
 *  - The Data API provides SEARCH and METADATA only; it does not provide audio streams.
 *  - A search costs 100 quota units of the free 10 000/day budget (~100 searches/day per key),
 *    so the app only calls it when the user explicitly selects the "YT API" filter.
 *  - The key is the user's own: configured at runtime in app settings, never committed.
 */
class YouTubeDataNotConfiguredException :
    IllegalStateException("YouTube Data API key is not configured")

/** API-level error reported by Google (invalid key, quota exceeded, bad request...). */
class YouTubeDataApiException(val reason: String?, message: String) :
    IllegalStateException(message)

object YouTubeData {
    private const val API_BASE = "https://youtube.googleapis.com/youtube/v3/search"

    /** Users add their own (free) API key in app Settings > Content; null = provider off. */
    @Volatile
    var apiKey: String? = null

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            if (YouTube.proxy != null) {
                engine {
                    proxy = YouTube.proxy
                }
            }
            install(ContentNegotiation) { json(json) }
            install(ContentEncoding) {
                brotli(1.0F)
                gzip(0.9F)
                deflate(0.8F)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
            }
        }
    }

    private fun requireApiKey(): String =
        apiKey?.takeIf { it.isNotBlank() } ?: throw YouTubeDataNotConfiguredException()

    /**
     * Video search. Results carry only permitted metadata: video id, title, channel name,
     * thumbnail, duration, live flag - exactly the fields the Data API is meant for.
     */
    suspend fun search(
        query: String,
        pageToken: String? = null,
        limit: Int = 25,
    ): YouTubeDataSearchResult {
        try {
            val response: HttpResponse = client.get(API_BASE) {
                parameter("key", requireApiKey())
                parameter("part", "snippet,contentDetails")
                parameter("type", "video")
                parameter("q", query)
                parameter("maxResults", limit)
                parameter("safeSearch", "none")
                if (pageToken != null) parameter("pageToken", pageToken)
            }

            if (!response.status.isSuccess()) {
                // Google documents error envelopes for HTTP errors (invalid key, quota).
                val errorEnvelope = response.body<YouTubeDataSearchResponse>().error
                    ?: error("YouTube Data API request failed (HTTP ${response.status.value})")
                throwEnvelope(errorEnvelope)
            }

            val searchResponse = response.body<YouTubeDataSearchResponse>()
            searchResponse.error?.let(::throwEnvelope)
            return searchResponse.toResult()
        } catch (e: Throwable) {
            // keep NotConfiguredException intact for honest surfacing in the app
            if (e is YouTubeDataNotConfiguredException) throw e
            if (e is YouTubeDataApiException) throw e
            throw IllegalStateException("YouTube Data API request failed: ${e.message}", e)
        }
    }

    private fun throwEnvelope(error: YouTubeDataErrorEnvelope): Nothing {
        val reason = error.errors.firstOrNull()?.reason
        val message = when {
            reason == "quotaExceeded" ->
                "YouTube Data API quota exceeded (limit resets daily)"

            reason == "keyInvalid" || reason == "expiredKey" || reason?.startsWith("key") == true ->
                "Your YouTube Data API key is invalid or expired"

            error.message.isNullOrBlank() ->
                "YouTube Data API request failed (code ${error.code})"

            else -> error.message
        }
        throw YouTubeDataApiException(reason, message)
    }

    private fun YouTubeDataSearchResponse.toResult() = YouTubeDataSearchResult(
        videos = items.mapNotNull { item ->
            val videoId = item.id?.videoId ?: return@mapNotNull null
            val snippet = item.snippet ?: return@mapNotNull null
            val title = snippet.title ?: return@mapNotNull null
            val thumbnails = snippet.thumbnails
            YouTubeDataVideo(
                videoId = videoId,
                title = title,
                channelId = snippet.channelId,
                channelTitle = snippet.channelTitle ?: "Unknown channel",
                thumbnail = thumbnails.bestUrl(),
                durationSeconds = item.contentDetails?.duration?.isoDurationToSeconds(),
                live = snippet.liveBroadcastContent == "live",
            )
        },
        nextPageToken = nextPageToken,
    )

    private fun YouTubeDataThumbnails?.bestUrl(): String? = this?.let { t ->
        t.maxres?.url ?: t.high?.url ?: t.medium?.url ?: t.default?.url
    }
}

/**
 * ISO-8601 duration from the Data API (e.g. "PT3M33S" -> 213, "PT1H2M3S" -> 3723).
 * Returns null when the duration is absent or in an unexpected shape; callers must
 * treat null as unknown rather than inventing a fake duration.
 */
fun String.isoDurationToSeconds(): Int? {
    if (isBlank()) return null
    val match = Regex("^P(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)$").matchEntire(this) ?: return null
    val (hours, minutes, seconds) = match.destructured
    val totalSeconds = (hours.toIntOrNull() ?: 0) * 3600 +
        (minutes.toIntOrNull() ?: 0) * 60 +
        (seconds.toIntOrNull() ?: 0)
    return if (totalSeconds > 0) totalSeconds else null
}

data class YouTubeDataSearchResult(
    val videos: List<YouTubeDataVideo>,
    val nextPageToken: String?,
)

@kotlinx.serialization.Serializable
data class YouTubeDataVideo(
    val videoId: String,
    val title: String,
    val channelId: String?,
    val channelTitle: String?,
    val thumbnail: String?,
    val durationSeconds: Int?,
    val live: Boolean = false,
)
