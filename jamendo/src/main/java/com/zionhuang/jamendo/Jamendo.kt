package com.zionhuang.jamendo

import com.zionhuang.jamendo.models.JamendoTrack
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Jamendo is a free music platform hosting tracks distributed under
 * Creative Commons licences (https://dev.jamendo.com/). Its official v3 API
 * returns genuine full-length stream/download URLs for tracks in its own
 * catalogue - no ad-supported extraction of third-party catalogues involved.
 *
 * Usage requires a free client id from https://devportal.jamendo.com/ uptime
 * registration ("Add Yours" > Application). The key is user-supplied at
 * runtime (app Settings > Content) and is intentionally NOT part of the
 * repository or the built-in default configuration.
 */
class NotConfiguredException : IllegalStateException("Jamendo client id is not configured")

object Jamendo {
    private const val API_BASE = "https://api.jamendo.com/v3.0"

    /** Users set their own (free) client id in app settings; null = provider off. */
    @Volatile
    var clientId: String? = null

    private val client by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    },
                )
            }

            defaultRequest {
                url(API_BASE)
            }

            expectSuccess = true
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
        }
    }

    private fun requireClientId(): String = clientId?.takeIf { it.isNotBlank() } ?: throw NotConfiguredException()

    /**
     * Full-text track search. Every returned track carries a direct `audio`
     * stream URL and a usable `audiodownload` URL - both are official Jamendo
     * API-provided links for CC-licensed tracks and completion is guaranteed
     * (full-length files served by Jamendo's own CDN).
     */
    suspend fun search(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): List<JamendoTrack> = runCatching {
        client.get("tracks/") {
            parameter("client_id", requireClientId())
            parameter("format", "json")
            parameter("search", query)
            parameter("limit", limit)
            parameter("offset", offset)
            // audio/metadata only - we do not use (or pay for) audioextradata
            parameter("audioformat", "mp31")
            }.body<JamendoSearchResponse>().requireValid().results
    }
        .onFailure {
            // keep NotConfiguredException intact for honest surfacing in the app
            if (it !is NotConfiguredException) throw IllegalStateException("Jamendo search failed: ${it.message}", it)
        }
        .getOrThrow()

    /**
     * Resolve the official full-length stream URL for a track id
     * (audio URLs returned by Jamendo are stable links to the whole file).
     */
    suspend fun streamUrl(trackId: String): String {
        val results = client.get("tracks/") {
            parameter("client_id", requireClientId())
            parameter("format", "json")
            parameter("id", trackId)
            parameter("limit", 1)
            parameter("audioformat", "mp31")
        }.body<JamendoSearchResponse>().requireValid().results
        val track = results.firstOrNull() ?: throw IllegalStateException("Jamendo track not found: $trackId")
        return track.audio ?: throw IllegalStateException("Jamendo track has no stream URL: $trackId")
    }

    /**
     * Jamendo signals misconfiguration (invalid client id and similar) with
     * HTTP 200 plus headers.status="failed". The app must surface that as a
     * real error message instead of silently showing "no results".
     */
    private fun JamendoSearchResponse.requireValid(): JamendoSearchResponse = also {
        val headerStatus = headers?.status
        if (headerStatus != null && headerStatus != "success") {
            throw IllegalStateException(
                headers.error_message?.takeIf { it.isNotBlank() }
                    ?: "Jamendo request failed (code ${headers.code})"
            )
        }
    }
}

/** Top-level v3.0 response envelope: {"headers": {...}, "results": [...]}. */
@kotlinx.serialization.Serializable
data class JamendoSearchResponse(
    val headers: JamendoHeaders? = null,
    val results: List<JamendoTrack> = emptyList(),
)

@kotlinx.serialization.Serializable
data class JamendoHeaders(
    val status: String? = null,
    val code: Int? = null,
    val results_count: Int? = null,
    val error_message: String? = null,
)
