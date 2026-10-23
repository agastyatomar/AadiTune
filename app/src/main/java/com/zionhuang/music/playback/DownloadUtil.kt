package com.zionhuang.music.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.response.AudioFormatSelection
import com.zionhuang.saavn.Saavn
import com.zionhuang.music.constants.AudioQuality
import com.zionhuang.music.constants.AudioQualityKey
import com.zionhuang.music.db.MusicDatabase
import com.zionhuang.music.db.entities.FormatEntity
import com.zionhuang.music.di.DownloadCache
import com.zionhuang.music.di.PlayerCache
import com.zionhuang.music.utils.enumPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil @Inject constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.HIGH)
    private val dataSourceFactory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(
                    OkHttpClient.Builder()
                        .proxy(YouTube.proxy)
                        .build()
                )
            )
    ) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")

        // Saavn is the OFFLINE-capable provider: its official full-length MP3s
        // carry no CDN byte cap, so the download flow streams the whole file and
        // saves it in the app library (no subrange chopping needed either).
        if (mediaId.startsWith(MusicService.SAAVN_MEDIA_ID_PREFIX)) {
            val streamUrl = runBlocking(Dispatchers.IO) {
                runCatching {
                    kotlinx.coroutines.withTimeout(20_000L) {
                        Saavn.streamUrl(mediaId.removePrefix(MusicService.SAAVN_MEDIA_ID_PREFIX))
                    }
                }
            }.getOrElse { throwable ->
                throw PlaybackException(
                    "Download failed: ${throwable.message ?: "Saavn stream resolution"}",
                    throwable,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR
                )
            }
            return@Factory dataSpec.withUri(streamUrl.toUri())
        }

        // Free/open providers (Jamendo + Audius) are never download queue items:
        // official licence terms restrict redistribution and the download flow is
        // YouTube-id oriented.
        if (mediaId.startsWith(MusicService.JAMENDO_MEDIA_ID_PREFIX) ||
            mediaId.startsWith(MusicService.AUDIUS_MEDIA_ID_PREFIX)
        ) {
            throw PlaybackException(
                "Streaming only - downloads for the free provider are not supported",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        val length = if (dataSpec.length >= 0) dataSpec.length else 1

        if (playerCache.isCached(mediaId, dataSpec.position, length)) {
            return@Factory dataSpec
        }

        val playedFormat = runBlocking(Dispatchers.IO) { database.format(mediaId).first() }
        val playerResponse = runCatching {
            runBlocking(Dispatchers.IO) {
                kotlinx.coroutines.withTimeout(20_000L) {
                    YouTube.player(mediaId).getOrThrow()
                }
            }
        }.getOrElse { throwable ->
            if (throwable is TimeoutCancellationException) {
                throw PlaybackException(
                    "Resolving stream timed out",
                    throwable,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                )
            }
            throw if (throwable is PlaybackException) throwable
            else PlaybackException(throwable.message ?: "Resolution failure", throwable, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }
        if (playerResponse.playabilityStatus.status != "OK") {
            throw PlaybackException(playerResponse.playabilityStatus.reason, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }
        val streamingData = playerResponse.streamingData
            ?: throw PlaybackException("No stream available", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        if (playerResponse.isLive) {
            throw PlaybackException("Live streams cannot be downloaded", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }

        val durationSeconds = playerResponse.videoDetails?.lengthSeconds?.toLongOrNull()
        val audioCandidates = streamingData.adaptiveFormats
            .filter { it.isAudio && it.url != null }
        val format = (
            if (playedFormat != null) {
                // Reject preview-sized formats that a provider returned for a full track.
                audioCandidates.find { it.itag == playedFormat.itag }
                    ?.takeUnless { AudioFormatSelection.isDefinitelyPreview(it, durationSeconds) }
            } else {
                // Prefer full-length formats: reject 30-second "sample" responses.
                val fullLength =
                    audioCandidates.filter { AudioFormatSelection.isFullLength(it, durationSeconds) == true }
                (fullLength.ifEmpty { audioCandidates.filter { f -> f.contentLength != null } })
                    .maxByOrNull {
                        it.bitrate * when (audioQuality) {
                            AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                            AudioQuality.HIGH -> 1
                            AudioQuality.LOW -> -1
                        } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
                    }
            }
        ).takeIf { it?.url != null && it.contentLength != null }
            ?: throw PlaybackException("No stream available", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)

        val rangedFormat = format.copy(
            // Specify range to avoid YouTube's throttling
            url = "${format.url}&range=0-${format.contentLength}"
        )

        database.query {
            upsert(
                FormatEntity(
                    id = mediaId,
                    itag = format.itag,
                    mimeType = format.mimeType.split(";")[0],
                    codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                    bitrate = format.bitrate,
                    sampleRate = format.audioSampleRate,
                    contentLength = format.contentLength ?: -1L,
                    loudnessDb = playerResponse.playerConfig?.audioConfig?.loudnessDb
                )
            )
        }

        // Bounded full-tail range for downloads as well (logcat evidence shows the
        // CDN rejects open-ended ranges and frequency-caps repeated span opens);
        // one bounded read fills the download cache completely.
        val downloadBytes = rangedFormat.contentLength!!
        val downloadTailBytes = (downloadBytes - dataSpec.position).coerceIn(1L, downloadBytes)
        dataSpec.withUri(rangedFormat.url!!.toUri()).subrange(0L, downloadTailBytes)
    }
    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)
    val downloadManager: DownloadManager = DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
        maxParallelDownloads = 3
        addListener(
            ExoDownloadService.TerminalStateNotificationHelper(
                context = context,
                notificationHelper = downloadNotificationHelper,
                nextNotificationId = ExoDownloadService.NOTIFICATION_ID + 1
            )
        )
    }
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    fun getDownload(songId: String?): Flow<Download?> = downloads.map { it[songId] }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result
        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    downloads.update { map ->
                        map.toMutableMap().apply {
                            set(download.request.id, download)
                        }
                    }
                }
            }
        )
    }
}