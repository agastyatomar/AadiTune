package com.zionhuang.saavn.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class SaavnSearchResponse(
    val success: Boolean? = null,
    val data: SaavnSearchData? = null,
)

@Serializable
data class SaavnSearchData(
    val total: Int? = null,
    val start: Int? = null,
    val results: List<SaavnTrack> = emptyList(),
)

@Serializable
data class SaavnTrack(
    val id: String,
    val name: String? = null,
    val year: String? = null,
    val duration: String? = null, // seconds, as a string
    @SerialName("primaryArtists")
    val primaryArtists: String? = null,
    val album: SaavnAlbum? = null,
    @SerialName("downloadUrl")
    val downloadUrl: List<SaavnMediaLink> = emptyList(),
    @SerialName("image")
    val image: List<SaavnMediaLink> = emptyList(),
    val hasLyrics: Boolean? = null,
    val language: String? = null,
    @SerialName("explicitContent")
    val explicitContent: String? = null,
) {
    fun durationSeconds(): Int = duration?.toIntOrNull() ?: 0

    fun isExplicit(): Boolean = explicitContent.equals("true", ignoreCase = true)

    /** Preferred latest highest-quality link; falls back down the list. */
    fun bestStreamUrl(): String? {
        if (downloadUrl.isEmpty()) return null
        val preferred = downloadUrl.firstOrNull { it.quality?.contains("320") == true }
            ?: downloadUrl.firstOrNull { it.quality?.contains("160") == true }
            ?: downloadUrl.firstOrNull { it.quality?.contains("128") == true }
        return (preferred ?: downloadUrl.last()).link
    }

    fun bestThumbUrl(): String? = image.lastOrNull()?.link ?: image.firstOrNull()?.link
}

@Serializable
data class SaavnAlbum(
    val id: String? = null,
    val name: String? = null,
    @SerialName("image")
    val image: List<SaavnMediaLink> = emptyList(),
) {
    /** saavn.dev sometimes emits `null` for album image arrays here. */
    fun imageSafe(): List<SaavnMediaLink> = image
}

@Serializable
data class SaavnMediaLink(
    val quality: String? = null,
    val link: String? = null,
)
