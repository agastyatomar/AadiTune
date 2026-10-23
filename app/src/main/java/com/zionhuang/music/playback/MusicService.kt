package com.zionhuang.music.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.response.AudioFormatSelection
import com.zionhuang.innertube.models.response.PlayerResponse
import com.zionhuang.audius.Audius
import com.zionhuang.jamendo.Jamendo
import com.zionhuang.jamendo.NotConfiguredException
import com.zionhuang.music.MainActivity
import com.zionhuang.music.R
import com.zionhuang.music.constants.AudioNormalizationKey
import com.zionhuang.music.constants.AudioQuality
import com.zionhuang.music.constants.AudioQualityKey
import com.zionhuang.music.constants.AutoLoadMoreKey
import com.zionhuang.music.constants.AutoSkipNextOnErrorKey
import com.zionhuang.music.constants.HideExplicitKey
import com.zionhuang.music.constants.MediaSessionConstants.CommandToggleLibrary
import com.zionhuang.music.constants.MediaSessionConstants.CommandToggleLike
import com.zionhuang.music.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.zionhuang.music.constants.MediaSessionConstants.CommandToggleShuffle
import com.zionhuang.music.constants.PauseListenHistoryKey
import com.zionhuang.music.constants.PersistentQueueKey
import com.zionhuang.music.constants.PlayerVolumeKey
import com.zionhuang.music.constants.RepeatModeKey
import com.zionhuang.music.constants.ShowLyricsKey
import com.zionhuang.music.constants.SkipSilenceKey
import com.zionhuang.music.db.MusicDatabase
import com.zionhuang.music.db.entities.Event
import com.zionhuang.music.db.entities.FormatEntity
import com.zionhuang.music.db.entities.LyricsEntity
import com.zionhuang.music.db.entities.RelatedSongMap
import com.zionhuang.music.di.DownloadCache
import com.zionhuang.music.di.PlayerCache
import com.zionhuang.music.extensions.SilentHandler
import com.zionhuang.music.extensions.collect
import com.zionhuang.music.extensions.collectLatest
import com.zionhuang.music.extensions.currentMetadata
import com.zionhuang.music.extensions.findNextMediaItemById
import com.zionhuang.music.extensions.mediaItems
import com.zionhuang.music.extensions.metadata
import com.zionhuang.music.extensions.toMediaItem
import com.zionhuang.music.lyrics.LyricsHelper
import com.zionhuang.music.models.PersistQueue
import com.zionhuang.music.models.toMediaMetadata
import com.zionhuang.music.playback.queues.EmptyQueue
import com.zionhuang.music.playback.queues.ListQueue
import com.zionhuang.music.playback.queues.Queue
import com.zionhuang.music.playback.queues.YouTubeQueue
import com.zionhuang.music.playback.queues.filterExplicit
import com.zionhuang.music.utils.CoilBitmapLoader
import com.zionhuang.music.utils.dataStore
import com.zionhuang.music.utils.enumPreference
import com.zionhuang.music.utils.get
import com.zionhuang.music.utils.isInternetAvailable
import com.zionhuang.music.utils.reportException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds


@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService : MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    private var scope = CoroutineScope(Dispatchers.Main) + Job()
    private val binder = MusicBinder()

    private lateinit var connectivityManager: ConnectivityManager

    private val audioQuality by enumPreference(this, AudioQualityKey, AudioQuality.HIGH)

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null

    val currentMediaMetadata = MutableStateFlow<com.zionhuang.music.models.MediaMetadata?>(null)
    private val currentSong = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.song(mediaMetadata?.id)
    }.stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.format(mediaMetadata?.id)
    }

    private val normalizeFactor = MutableStateFlow(1f)
    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    /** Stream URL cache: mediaId to (url, absolute expiry millis) */

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    private var isAudioEffectSessionOpened = false

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this, { NOTIFICATION_ID }, CHANNEL_ID, R.string.music_player)
                .apply {
                    setSmallIcon(R.drawable.small_icon)
                }
        )
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(createMediaSourceFactory())
            .setRenderersFactory(createRenderersFactory())
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .apply {
                addListener(this@MusicService)
                sleepTimer = SleepTimer(scope, this)
                addListener(sleepTimer)
                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
            }
        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleLibrary = ::toggleLibrary
        }
        mediaSession = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setBitmapLoader(CoilBitmapLoader(this, scope))
            .build()
        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!

        combine(playerVolume, normalizeFactor) { playerVolume, normalizeFactor ->
            playerVolume * normalizeFactor
        }.collectLatest(scope) {
            player.volume = it
        }

        playerVolume.debounce(1000).collect(scope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(1000).collect(scope) { song ->
            updateNotification()
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged()
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(scope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database.lyrics(mediaMetadata.id).first() == null) {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = lyrics
                        )
                    )
                }
            }
        }

        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                player.skipSilenceEnabled = it
            }

        combine(
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged()
        ) { format, normalizeAudio ->
            format to normalizeAudio
        }.collectLatest(scope) { (format, normalizeAudio) ->
            normalizeFactor.value = if (normalizeAudio && format?.loudnessDb != null) {
                min(10f.pow(-format.loudnessDb.toFloat() / 20), 1f)
            } else {
                1f
            }
        }

        if (dataStore.get(PersistentQueueKey, true)) {
            runCatching {
                filesDir.resolve(PERSISTENT_QUEUE_FILE).inputStream().use { fis ->
                    ObjectInputStream(fis).use { oos ->
                        oos.readObject() as PersistQueue
                    }
                }
            }.onSuccess { queue ->
                playQueue(
                    queue = ListQueue(
                        title = queue.title,
                        items = queue.items.map { it.toMediaItem() },
                        startIndex = queue.mediaItemIndex,
                        position = queue.position
                    ),
                    playWhenReady = false
                )
            }
        }

        // Save queue periodically to prevent queue loss from crash or force kill
        scope.launch {
            while (isActive) {
                delay(30.seconds)
                if (dataStore.get(PersistentQueueKey, true)) {
                    saveQueueToDisk()
                }
            }
        }
    }

    private fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton.Builder()
                    .setDisplayName(getString(if (currentSong.value?.song?.inLibrary != null) R.string.remove_from_library else R.string.add_to_library))
                    .setIconResId(if (currentSong.value?.song?.inLibrary != null) R.drawable.library_add_check else R.drawable.library_add)
                    .setSessionCommand(CommandToggleLibrary)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(getString(if (currentSong.value?.song?.liked == true) R.string.action_remove_like else R.string.action_like))
                    .setIconResId(if (currentSong.value?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border)
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            }
                        )
                    )
                    .setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        }
                    )
                    .setSessionCommand(CommandToggleRepeatMode)
                    .build()
            )
        )
    }

    private suspend fun recoverSong(mediaId: String, playerResponse: PlayerResponse? = null) {
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playerResponse ?: YouTube.player(mediaId).getOrNull())?.videoDetails?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            if (song == null) insert(mediaMetadata.copy(duration = duration))
            else if (song.song.duration == -1) update(song.song.copy(duration = duration))
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint = YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id
                        )
                    }
                    .forEach(::insert)
            }
        }
    }

    fun playQueue(queue: Queue, playWhenReady: Boolean = true) {
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main) + Job()
        }
        currentQueue = queue
        queueTitle = null
        player.shuffleModeEnabled = false
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }

        scope.launch(SilentHandler) {
            val initialStatus = withContext(Dispatchers.IO) {
                queue.getInitialStatus().filterExplicit(dataStore.get(HideExplicitKey, false))
            }
            if (queue.preloadItem != null && player.playbackState == STATE_IDLE) return@launch
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            if (queue.preloadItem != null) {
                // add missing songs back, without affecting current playing song
                player.addMediaItems(0, initialStatus.items.subList(0, initialStatus.mediaItemIndex))
                player.addMediaItems(initialStatus.items.subList(initialStatus.mediaItemIndex + 1, initialStatus.items.size))
            } else {
                player.setMediaItems(initialStatus.items, if (initialStatus.mediaItemIndex > 0) initialStatus.mediaItemIndex else 0, initialStatus.position)
                player.prepare()
                player.playWhenReady = playWhenReady
            }
        }
    }

    fun startRadioSeamlessly() {
        val currentMediaMetadata = player.currentMetadata ?: return
        if (player.currentMediaItemIndex > 0) player.removeMediaItems(0, player.currentMediaItemIndex)
        if (player.currentMediaItemIndex < player.mediaItemCount - 1) player.removeMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount)
        scope.launch(SilentHandler) {
            val radioQueue = YouTubeQueue(endpoint = WatchEndpoint(videoId = currentMediaMetadata.id))
            val initialStatus = radioQueue.getInitialStatus()
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            player.addMediaItems(initialStatus.items.drop(1))
            currentQueue = radioQueue
        }
    }

    fun playNext(items: List<MediaItem>) {
        player.addMediaItems(if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1, items)
        player.prepare()
    }

    fun addToQueue(items: List<MediaItem>) {
        player.addMediaItems(items)
        player.prepare()
    }

    fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLike())
            }
        }
    }

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Auto load more songs
        if (dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            currentQueue.hasNextPage()
        ) {
            scope.launch(SilentHandler) {
                val mediaItems = currentQueue.nextPage().filterExplicit(dataStore.get(HideExplicitKey, false))
                if (player.playbackState != STATE_IDLE) {
                    player.addMediaItems(mediaItems)
                }
            }
        }
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            // Stream worked; reset its retry counter.
            player.currentMediaItem?.mediaId?.let { streamRetryAttempts.remove(it) }
        }
        if (playbackState == STATE_IDLE) {
            currentQueue = EmptyQueue
            player.shuffleModeEnabled = false
            queueTitle = null
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
            val isBufferingOrReady = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }
    }


    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        if (shuffleModeEnabled) {
            // Always put current playing item at first
            val shuffledIndices = IntArray(player.mediaItemCount) { it }
            shuffledIndices.shuffle()
            shuffledIndices[shuffledIndices.indexOf(player.currentMediaItemIndex)] = shuffledIndices[0]
            shuffledIndices[0] = player.currentMediaItemIndex
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
    }

    /** Per-mediaId retry counter for stream errors (expired/403 stream URLs cause mid-play stops). */
    private val streamRetryAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    override fun onPlayerError(error: PlaybackException) {
        val mediaId = player.currentMediaItem?.mediaId
        // No sensitive data here: provider error code, media id and the playback
        // position - enough to diagnose mid-play stops (e.g. ~30s truncation).
        Timber.w("playback-error: code=%d positionMs=%d mediaId=%s", error.errorCode, player.currentPosition, mediaId)
        // Live streams cannot be resolved unauthenticated: don't burn retries, just skip on.
        if (error.errorCode == ERROR_CODE_LIVE_UNSUPPORTED) {
            if (isInternetAvailable(this) && player.hasNextMediaItem()) {
                player.seekToNext()
                player.prepare()
                player.playWhenReady = true
            }
            return
        }
        // Truncation guards (preview-sized format) are not transient: retrying the same
        // item would loop on the same limitation, so move on like the live case.
        if (error.errorCode == ERROR_CODE_PREVIEW_ONLY) {
            if (isInternetAvailable(this) && player.hasNextMediaItem()) {
                player.seekToNext()
                player.prepare()
                player.playWhenReady = true
            }
            return
        }
        // Purge the cached stream URL / partially cached audio of the failing item so that
        // the retry fetches a fresh stream instead of replaying a stale (possibly truncated) one.
            mediaId?.let { id ->
                runCatching { playerCache.removeResource(id) }
            // Silently retry the same item with a fresh stream URL a few times before giving up.
            val attempts = streamRetryAttempts[id] ?: 0
            if (attempts < 2) {
                streamRetryAttempts[id] = attempts + 1
                runCatching {
                    player.prepare()
                    player.playWhenReady = true
                }
                return
            }
            streamRetryAttempts.remove(id)
        }
        if (dataStore.get(AutoSkipNextOnErrorKey, false) &&
            isInternetAvailable(this) &&
            player.hasNextMediaItem()
        ) {
            player.seekToNext()
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun createCacheDataSource(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                OkHttpClient.Builder()
                                    .proxy(YouTube.proxy)
                                    .addInterceptor { chain ->
                                        val req = chain.request()
                                        if (req.header("User-Agent") == null) {
                                            chain.proceed(
                                                req.newBuilder()
                                                    .header("User-Agent", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)")
                                                    .build()
                                            )
                                        } else chain.proceed(req)
                                    }
                                    .build()
                            )
                        )
                    )
            )
            .setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    private fun createDataSourceFactory(): DataSource.Factory {
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")

            // Jamendo (free Creative Commons catalogue): ids are namespaced "jamendo:<trackId>".
            // Never routed through the YouTube/InnerTube resolution below.
            if (mediaId.startsWith(JAMENDO_MEDIA_ID_PREFIX)) {
                val streamUrl = runBlocking(Dispatchers.IO) {
                    runCatching {
                        withTimeout(RESOLUTION_TIMEOUT_MS) {
                            Jamendo.streamUrl(mediaId.removePrefix(JAMENDO_MEDIA_ID_PREFIX))
                        }
                    }
                }.getOrElse { throwable ->
                    throw PlaybackException(
                        when {
                            throwable is NotConfiguredException -> getString(R.string.jamendo_not_configured)
                            else -> throwable.message?.takeIf { it.isNotBlank() } ?: getString(R.string.error_unknown)
                        },
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
                // Official Jamendo stream URLs point at a full, CC-licensed file and
                // do not expire; no subrange fetching needed.
                return@Factory dataSpec.withUri(streamUrl.toUri())
            }

            // Audius (open catalogue from public-apis): ids are "audius:<trackId>".
            // Streams are the provider's own redirect endpoints serving the full file
            // to anyone - no auth, no subrange, no key required.
            if (mediaId.startsWith(AUDIUS_MEDIA_ID_PREFIX)) {
                val streamUrl = runBlocking(Dispatchers.IO) {
                    runCatching {
                        withTimeout(RESOLUTION_TIMEOUT_MS) {
                            Audius.streamUrl(mediaId.removePrefix(AUDIUS_MEDIA_ID_PREFIX))
                        }
                    }
                }.getOrElse { throwable ->
                    throw PlaybackException(
                        throwable.message?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.error_unknown),
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
                return@Factory dataSpec.withUri(streamUrl.toUri())
            }

            // There may be inconsistent between the downloaded file and the displayed info if user change audio quality frequently
            val playedFormat = runBlocking(Dispatchers.IO) { database.format(mediaId).first() }

            // Only serve data through the cache shortcut when the cache PROVABLY covers
            // this whole open. Unresolved DataSpecs carry the raw media id as their URI,
            // which is not a playable source - serving them beyond the cached span was a
            // root cause of playback stopping at the cache boundary (~32s of 512KB opus).
            val openLength: Long? = when {
                dataSpec.length >= 0 -> dataSpec.length
                (playedFormat?.contentLength ?: 0) > 0 -> playedFormat!!.contentLength - dataSpec.position
                else -> null
            }
            if (openLength != null && openLength > 0 &&
                (downloadCache.isCached(mediaId, dataSpec.position, openLength) ||
                    playerCache.isCached(mediaId, dataSpec.position, openLength))
            ) {
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                return@Factory dataSpec
            }

            // NOTE: the old "reuse remembered stream URL" shortcut is intentionally
            // gone. Live logcat evidence (HTTP 403 on the second DataSpec open at the
            // 512KB boundary) proved signed CDN urls are effectively one-shot: the
            // follow-up ranged open on the SAME url fails with 403, which froze
            // playback at ~28s. Every open now gets its own freshly resolved url,
            // and the url itself is probed before it is served (see below).
            Timber.w("resolve: id=%s pos=%d -> resolving player response", mediaId, dataSpec.position)
            resolvePlaybackSpec(mediaId, dataSpec)
        }
    }

    /**
     * Cheap ranged probe (2 bytes) that verifies a signed url is actually accepted
     * by the CDN before we hand it to the loader. The url-production client may be
     * missing attestation in a given session; probing keeps that failure from being
     * delivered to the user as a dead player.
     */
    private fun probeStreamUrl(url: String): Boolean = try {
        val request = Request.Builder().url(url).header("Range", "bytes=0-1").build()
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val code = client.newCall(request).execute().use { it.code }
        Timber.i("probe: code=%d url-host=%s", code, url.substringBefore('/', "https://"))
        when (code) {
            in 200..299 -> true
            else -> false
        }
    } catch (e: Exception) {
        Timber.w("probe: request failed: %s", e.javaClass.simpleName + " " + (e.message ?: ""))
        false
    }

    /**
     * Resolve a fresh playable source for the given YouTube media id and return the
     * served DataSpec. Each attempt walks an ordered list of InnerTube clients; when
     * the produced url fails the probe, the next order (attestation-free clients)
     * is tried. Errors map to honest PlaybackExceptions as before.
     */
    private fun resolvePlaybackSpec(mediaId: String, dataSpec: DataSpec): DataSpec {
        val defaultOrder = if (YouTube.cookie != null) {
            listOf(YouTubeClient.ANDROID_MUSIC, YouTubeClient.IOS, YouTubeClient.ANDROID_VR)
        } else {
            listOf(YouTubeClient.IOS, YouTubeClient.ANDROID_VR)
        }
        val clientOrders = buildList {
            add(defaultOrder)
            if (defaultOrder.first() != YouTubeClient.IOS) {
                add(listOf(YouTubeClient.IOS, YouTubeClient.ANDROID_VR))
            }
        }

        var lastError: Throwable? = null
        var playabilityReason: String? = null
        for ((orderIndex, order) in clientOrders.withIndex()) {
            val playerResponse = runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeout(RESOLUTION_TIMEOUT_MS) {
                        YouTube.player(mediaId, clientOrder = order).getOrThrow()
                    }
                }
            }.getOrElse { throwable ->
                if (orderIndex == clientOrders.lastIndex) {
                    when (throwable) {
                        is TimeoutCancellationException -> {
                            Timber.w("resolve: timed out after %dms", RESOLUTION_TIMEOUT_MS)
                            throw PlaybackException(
                                getString(R.string.error_timeout),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                            )
                        }

                        is ConnectException, is UnknownHostException ->
                            throw PlaybackException(
                                getString(R.string.error_no_internet),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                            )

                        is SocketTimeoutException ->
                            throw PlaybackException(
                                getString(R.string.error_timeout),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                            )

                        else -> throw PlaybackException(
                            getString(R.string.error_unknown),
                            throwable,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR
                        )
                    }
                } else {
                    Timber.w("resolve: order %d errored (%s), trying next", orderIndex, throwable.message)
                    lastError = throwable
                    null
                }
            }

            if (playerResponse == null) continue

            if (playerResponse.playabilityStatus.status != "OK") {
                playabilityReason = playerResponse.playabilityStatus.reason
                if (orderIndex == clientOrders.lastIndex) {
                    throw PlaybackException(
                        playabilityReason ?: getString(R.string.error_unknown),
                        null,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
                continue
            }
            if (playerResponse.isLive) {
                // Unauthenticated InnerTube clients cannot resolve live streams; fail cleanly
                // instead of crashing on missing contentLength/format fields further below.
                throw PlaybackException(getString(R.string.error_live_unsupported), null, ERROR_CODE_LIVE_UNSUPPORTED)
            }
            val streamingData: PlayerResponse.StreamingData? = playerResponse.streamingData
            if (streamingData == null) {
                if (orderIndex == clientOrders.lastIndex) {
                    throw PlaybackException(getString(R.string.error_no_stream), null, ERROR_CODE_NO_STREAM)
                }
                continue
            }

            val durationSeconds = playerResponse.videoDetails?.lengthSeconds?.toLongOrNull()
            val audioCandidates = streamingData.adaptiveFormats.filter { it.isAudio && it.url != null }
            val playedFormat = runBlocking(Dispatchers.IO) { database.format(mediaId).first() }

            var format: PlayerResponse.StreamingData.Format? = null
            if (playedFormat != null) {
                format = audioCandidates.find { it.itag == playedFormat.itag }
                    ?.takeUnless { AudioFormatSelection.isFullLength(it, durationSeconds) == false }
            }
            if (format == null) {
                val fullLength =
                    audioCandidates.filter { AudioFormatSelection.isFullLength(it, durationSeconds) == true }
                format = (fullLength.ifEmpty { audioCandidates.filter { f -> f.contentLength != null } })
                    .maxByOrNull {
                        it.bitrate * when (audioQuality) {
                            AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                            AudioQuality.HIGH -> 1
                            AudioQuality.LOW -> -1
                        } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
                    }
            }
            if (format?.url == null || format?.contentLength == null) {
                if (orderIndex == clientOrders.lastIndex) {
                    throw PlaybackException(getString(R.string.error_no_stream), null, ERROR_CODE_NO_STREAM)
                }
                continue
            }
            val playableFormat = format!!

            if (AudioFormatSelection.isDefinitelyPreview(playableFormat, durationSeconds)) {
                lastError = PlaybackException(getString(R.string.error_preview_only), null, ERROR_CODE_PREVIEW_ONLY)
                if (orderIndex == clientOrders.lastIndex) throw lastError
                continue
            }

            Timber.w(
                "resolve: order=%d format itag=%d len=%d dur=%s",
                orderIndex, playableFormat.itag, playableFormat.contentLength, durationSeconds,
            )

            if (!probeStreamUrl(playableFormat.url!!)) {
                lastError = PlaybackException(getString(R.string.error_no_stream), null, ERROR_CODE_NO_STREAM)
                if (orderIndex == clientOrders.lastIndex) throw lastError
                continue
            }

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = playableFormat.itag,
                        mimeType = playableFormat.mimeType.split(";")[0],
                        codecs = playableFormat.mimeType.split("codecs=")[1].removeSurrounding("\""),
                        bitrate = playableFormat.bitrate,
                        sampleRate = playableFormat.audioSampleRate,
                        contentLength = playableFormat.contentLength ?: -1L,
                        loudnessDb = playerResponse.playerConfig?.audioConfig?.loudnessDb
                    )
                )
            }
            scope.launch(Dispatchers.IO) { recoverSong(mediaId, playerResponse) }

            // Bounded full-tail range: one bounded open covers the WHOLE remaining
            // file. Logcat evidence at every stage:
            //   - open-ended "bytes=0-" ranges are rejected with HTTP 403;
            //   - bounded ranges are accepted (206) but a new open every ~1s
            //     (the old 512KB span chain) trips the CDN request-frequency cap
            //     and playback dies around ~1.3MB (~52s of audio).
            // A single bounded read from the current position to EOF matches one
            // normal CDN session pattern and plays the COMPLETE song. It also
            // fully fills the player cache, making the track effectively offline.
            val contentBytes = playableFormat.contentLength!!
            val tailBytes = (contentBytes - dataSpec.position).coerceIn(1L, contentBytes)
            return dataSpec.withUri(playableFormat.url!!.toUri()).subrange(0L, tailBytes)
        }
        throw PlaybackException(getString(R.string.error_no_stream), null, ERROR_CODE_NO_STREAM)
    }

    private fun createMediaSourceFactory() =
        DefaultMediaSourceFactory(
            createDataSourceFactory(),
            ExtractorsFactory {
                arrayOf(MatroskaExtractor(), FragmentedMp4Extractor())
            }
        )

    private fun createRenderersFactory() =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink.Builder(this@MusicService)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray(),
                        SilenceSkippingAudioProcessor(2_000_000, 0.01f, 2_000_000, 0, 256),
                        SonicAudioProcessor()
                    )
                )
                .build()
        }

    override fun onPlaybackStatsReady(eventTime: AnalyticsListener.EventTime, playbackStats: PlaybackStats) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        if (playbackStats.totalPlayTimeMs >= 30000 && !dataStore.get(PauseListenHistoryKey, false)) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs
                        )
                    )
                } catch (_: SQLException) {
                }
            }
        }
    }

    private fun saveQueueToDisk() {
        if (player.playbackState == STATE_IDLE) {
            filesDir.resolve(PERSISTENT_QUEUE_FILE).delete()
            return
        }
        val persistQueue = PersistQueue(
            title = queueTitle,
            items = player.mediaItems.mapNotNull { it.metadata },
            mediaItemIndex = player.currentMediaItemIndex,
            position = player.currentPosition
        )
        runCatching {
            filesDir.resolve(PERSISTENT_QUEUE_FILE).outputStream().use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(persistQueue)
                }
            }
        }.onFailure {
            reportException(it)
        }
    }

    override fun onDestroy() {
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
        mediaSession.release()
        player.removeListener(this)
        player.removeListener(sleepTimer)
        player.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Keep playing if the user swipes the app away while music is running.
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val ERROR_CODE_LIVE_UNSUPPORTED = 1000002
        const val ERROR_CODE_PREVIEW_ONLY = 1000003
        const val JAMENDO_MEDIA_ID_PREFIX = "jamendo:"
        const val AUDIUS_MEDIA_ID_PREFIX = "audius:"
        const val CHUNK_LENGTH = 512 * 1024L

        /** Resolver network work must never hang the loader thread silently. */
        const val RESOLUTION_TIMEOUT_MS = 20_000L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
    }
}
