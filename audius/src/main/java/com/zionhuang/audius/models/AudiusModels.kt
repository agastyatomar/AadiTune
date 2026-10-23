package com.zionhuang.audius.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Normalized Audius track. Audius is an independent, openly-licensed streaming
 * catalogue listed in the public "public-apis" repository; every track streams
 * from its official endpoint full length, with no API credential required.
 */
@Serializable
data class AudiusTrack(
    val id: String,
    val title: String,
    val user: AudiusUser? = null,
    @SerialName("playback_seconds")
    val playbackSeconds: Int? = null,
    val duration: Int? = null, // legacy field, milliseconds
    val artwork: AudiusArtwork? = null,
    @SerialName("play_count")
    val playCount: Int? = null,
    val genre: String? = null,
) {
    /** playback_seconds is the modern field; duration (ms) is the legacy fallback. */
    fun durationSeconds(): Int = playbackSeconds ?: ((duration ?: 0) / 1000)
}

@Serializable
data class AudiusUser(
    val name: String? = null,
)

/** Artwork object carries fixed square sizes; medium is preferred, small as fallback. */
@Serializable
data class AudiusArtwork(
    @SerialName("480x480")
    val medium: String? = null,
    @SerialName("150x150")
    val small: String? = null,
) {
    fun bestUrl(): String? = medium ?: small
}

/** Envelope of /v1/tracks/search and of the host discovery document. */
@Serializable
data class AudiusTracksResponse(
    val data: List<AudiusTrack>,
)

@Serializable
data class AudiusHostsResponse(
    val data: List<String>,
)
