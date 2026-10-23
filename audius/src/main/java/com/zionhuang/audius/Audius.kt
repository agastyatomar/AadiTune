package com.zionhuang.audius

import com.zionhuang.audius.models.AudiusHostsResponse
import com.zionhuang.audius.models.AudiusTrack
import com.zionhuang.audius.models.AudiusTracksResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Client for the Audius open music catalogue (public-apis entry), used as the
 * honest, no-credential full-length streaming provider of NovaTune.
 *
 * Audius exposes official stream endpoints that are playable by anyone:
 *   <app host>/v1/tracks/{track_id}/stream?app_name=NovaTune -> handles 30x to a CDN file,
 *   so OkHttp data sources can follow the redirect for free (no auth, no subrange).
 * All faults fall back to a well-known public discovery node, and every request
 * is timeboxed (silent resolver freezes were previously a reporting complaint).
 */
object Audius {
    const val APP_NAME = "NovaTune"

    /** Well-known public discovery node, picked when metadata discovery fails. */
    const val HOST_FALLBACK = "https://discoveryprovider.audius.co"

    private const val DISCOVERY_URL = "https://api.audius.co"

    /**
     * Requires no user-provided key; `app_name` is a public attribution bucket.
     */
    const val STREAM_SUFFIX = "/v1/tracks/%s/stream?app_name=$APP_NAME"
    const val SEARCH_ENDPOINT = "%s/v1/tracks/search?app_name=$APP_NAME&limit=%s&query=%s"

    /**
     * Errs when a search call fails for a content reason (rate limit or filter hats).
     */
    class RateLimitException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    @Volatile
    private var host: String? = null

    /**
     * Choose the fastest available Audius content node. First failure collapses
     * to HOST_FALLBACK so off-floor environments still serve music (only slower).
     */
    suspend fun ensureHost(): String {
        host?.let { return it }
        val discovered = runCatching {
            withContext(Dispatchers.IO) {
                val text = client.get(DISCOVERY_URL).bodyAsText()
                json.decodeFromString<AudiusHostsResponse>(text).data.firstOrNull()
            }
        }.getOrNull()
        return (discovered?.trimEnd('/')
            ?.takeIf { it.startsWith("https://") }
            ?: HOST_FALLBACK).also { host = it }
    }

    /**
     * Text-query the open catalogue. Results keep Audius' own ids, mapped by the
     * caller to namespaced media ids ("audius:<trackId>") when queued.
     */
    suspend fun search(query: String, limit: Int = 20, offset: Int = 0): List<AudiusTrack> {
        require(query.isNotBlank()) { "Audius search query must not be blank" }
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }

        val host = ensureHost()
        val text = runCatching {
            withContext(Dispatchers.IO) {
                client.get("$host/v1/tracks/search") {
                    parameter("query", query)
                    parameter("app_name", APP_NAME)
                    parameter("limit", limit.coerceAtMost(100))
                    parameter("offset", offset)
                }.bodyAsText()
            }
        }.getOrElse {
            throw RateLimitException(it.message ?: "Audius search failed")
        }

        return runCatching { json.decodeFromString<AudiusTracksResponse>(text).data }
            .getOrElse { AudiusTracksResponse(emptyList()).data }
    }

    /**
     * The official full-length stream URL. Named after the app for attribution;
     * the endpoint itself redirects to a public CDN file, follow-redirect-capable.
     */
    suspend fun streamUrl(trackId: String): String {
        require(trackId.isNotBlank()) { "Audius track id must not be blank" }
        return "${ensureHost()}/v1/tracks/$trackId/stream?app_name=$APP_NAME"
    }

    val headers: Map<String, String> = emptyMap()
}
