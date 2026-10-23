package com.zionhuang.innertube.models.response

import kotlinx.serialization.Serializable

/** Shapes of the official YouTube Data API v3 search endpoint. */

@Serializable
data class YouTubeDataSearchResponse(
    val nextPageToken: String? = null,
    val error: YouTubeDataErrorEnvelope? = null,
    val items: List<YouTubeDataItem> = emptyList(),
)

@Serializable
data class YouTubeDataErrorEnvelope(
    val code: Int? = null,
    val message: String? = null,
    val errors: List<YouTubeDataErrorDetail> = emptyList(),
)

@Serializable
data class YouTubeDataErrorDetail(
    val reason: String? = null,
    val message: String? = null,
)

@Serializable
data class YouTubeDataItem(
    val id: YouTubeDataItemId? = null,
    val snippet: YouTubeDataSnippet? = null,
    val contentDetails: YouTubeDataContentDetails? = null,
)

@Serializable
data class YouTubeDataItemId(
    val videoId: String? = null,
)

@Serializable
data class YouTubeDataSnippet(
    val title: String? = null,
    val channelTitle: String? = null,
    val channelId: String? = null,
    val thumbnails: YouTubeDataThumbnails? = null,
    val liveBroadcastContent: String? = null,
)

@Serializable
data class YouTubeDataContentDetails(
    val duration: String? = null,
)

@Serializable
data class YouTubeDataThumbnails(
    val default: YouTubeDataThumbnail? = null,
    val medium: YouTubeDataThumbnail? = null,
    val high: YouTubeDataThumbnail? = null,
    val maxres: YouTubeDataThumbnail? = null,
)

@Serializable
data class YouTubeDataThumbnail(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)
