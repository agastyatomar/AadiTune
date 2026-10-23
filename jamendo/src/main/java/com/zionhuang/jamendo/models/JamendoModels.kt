package com.zionhuang.jamendo.models

import kotlinx.serialization.Serializable

/**
 * Normalized provider track. Only carries fields the app actually needs;
 * `licenseRef` and `licenseDesc` are kept so the UI can show the track's
 * Creative Commons conditions honestly instead of pretending it is a
 * generic commercial-catalogue song.
 */
@Serializable
data class JamendoTrack(
    val id: String,
    val name: String,
    val duration: Int?, // seconds
    val artist_name: String,
    val artist_id: String? = null,
    val album_name: String? = null,
    val album_image: String? = null,
    val image: String? = null,
    val audio: String? = null, // official stream URL (full track)
    val audiodownload: String? = null, // official full download URL
    val audiodownload_allowed: Boolean? = null,
    val license_ccurl: String? = null,
    val license_desc: String? = null,
    val releasedate: String? = null,
)

/** Music info cart attached only when the caller passes include=musicinfo. */
@Serializable
data class JamendoMusicInfo(
    val reputation: Int? = null,
)
