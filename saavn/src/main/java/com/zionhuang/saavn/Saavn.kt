package com.zionhuang.saavn

import com.zionhuang.saavn.models.SaavnSearchResponse
import com.zionhuang.saavn.models.SaavnTrack
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Client for the saavn.dev public API (public, no key): searches JioSaavn's
 * catalogue and resolves OFFICIAL public stream URLs for FULL 128/320 kbps
 * MP3 files. This is the provider that plays complete songs for Hindi/Indian
 * and mainstream catalogues when the YouTube CDN caps unauthenticated ranges.
 */
object Saavn {
    const val HOST = "https://saavn.dev"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val client = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    suspend fun search(query: String, limit: Int = 20, offset: Int = 0): List<SaavnTrack> {
        require(query.isNotBlank()) { "Saavn search query must not be blank" }
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }

        val text = runCatching {
            withContext(Dispatchers.IO) {
                client.get("$HOST/api/search/songs") {
                    parameter("query", query)
                    parameter("limit", limit.coerceIn(1, 100))
                    parameter("page", (offset / limit.coerceAtLeast(1)) + 1)
                }.bodyAsText()
            }
        }.getOrElse { error(it.message ?: "Saavn search failed") }

        val response = runCatching { json.decodeFromString<SaavnSearchResponse>(text) }
            .getOrElse { error("Saavn search response parse failure") }
        return response.data?.results ?: emptyList()
    }

    /**
     * Resolve a concrete track directly by id; useful for the playback resolver
     * ("saavn:<trackId>" media ids) after app restarts without a search context.
     */
    suspend fun track(trackId: String): SaavnTrack? {
        require(trackId.isNotBlank()) { "Saavn track id must not be blank" }
        val text = runCatching {
            withContext(Dispatchers.IO) {
                client.get("$HOST/api/songs") {
                    parameter("ids", trackId)
                }.bodyAsText()
            }
        }.getOrElse { error(it.message ?: "Saavn track fetch failed") }
        val response = runCatching { json.decodeFromString<SaavnSearchResponse>(text) }
            .getOrElse { error("Saavn track response parse failure") }
        return response.data?.results?.firstOrNull { it.id == trackId }
    }

    suspend fun streamUrl(trackId: String): String {
        val track = track(trackId) ?: error("Saavn track $trackId not found")
        return track.bestStreamUrl() ?: error("Saavn track $trackId has no stream url")
    }

    val headers: Map<String, String> = emptyMap()
}
