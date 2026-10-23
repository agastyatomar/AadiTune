package com.zionhuang.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.YouTubeData
import com.zionhuang.innertube.YouTubeDataVideo
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.filterExplicit
import com.zionhuang.jamendo.Jamendo
import com.zionhuang.jamendo.NotConfiguredException
import com.zionhuang.jamendo.models.JamendoTrack
import com.zionhuang.innertube.pages.SearchSummaryPage
import com.zionhuang.music.constants.HideExplicitKey
import com.zionhuang.music.models.ItemsPage
import com.zionhuang.music.utils.dataStore
import com.zionhuang.music.utils.get
import com.zionhuang.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.collections.set

/**
 * Sentinel search filter: selects the free (Jamendo) provider instead of a
 * YouTube search. Its value never collides with YouTube's real search params
 * and is handled before any YouTube call is made.
 */
val SearchFilterFree = YouTube.SearchFilter("__jamendo__")

/**
 * Sentinel search filter: search through the OFFICIAL YouTube Data API v3 with
 * the user's own key (Settings > Content) instead of the InnerTube music search.
 * Handled before any InnerTube call; playback of these results still follows the
 * app's regular permitted YouTube paths (no stream extraction anywhere).
 */
val SearchFilterYouTubeData = YouTube.SearchFilter("__ytdapi__")

@HiltViewModel
class OnlineSearchViewModel @Inject constructor(
    @ApplicationContext context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = savedStateHandle.get<String>("query")!!
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    /** Last free-provider error (null if none). Surfaced in the search screen. */
    var freeSearchError by mutableStateOf<Throwable?>(null)
        private set

    /** Last YouTube Data API error (null if none). Surfaced in the search screen. */
    var youTubeDataSearchError by mutableStateOf<Throwable?>(null)
        private set

    private val hideExplicit get() = context.dataStore.get(HideExplicitKey, false)

    init {
        viewModelScope.launch {
            filter.collect { filter ->
                when (filter?.value) {
                    SearchFilterFree.value -> if (viewStateMap[SearchFilterFree.value] == null) loadFreePage(0)
                    SearchFilterYouTubeData.value -> if (viewStateMap[SearchFilterYouTubeData.value] == null) loadYouTubeDataPage(null)
                    null -> if (summaryPage == null) {
                        YouTube.searchSummary(query)
                            .onSuccess {
                                summaryPage = it.filterExplicit(hideExplicit)
                            }
                            .onFailure {
                                reportException(it)
                            }
                    }

                    else -> if (viewStateMap[filter.value] == null) {
                        YouTube.search(query, filter)
                            .onSuccess { result ->
                                viewStateMap[filter.value] = ItemsPage(
                                    result.items
                                        .distinctBy { it.id }
                                        .filterExplicit(hideExplicit),
                                    result.continuation
                                )
                            }
                            .onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    /**
     * Free-provider results must never be presented as a directly downloadable
     * file or as a YouTube item; each track keeps a "jamendo:" namespaced id so
     * the player resolves it against the Jamendo provider only.
     */
    private suspend fun loadFreePage(offset: Int) {
        try {
            freeSearchError = null
            val pageSize = 25
            val tracks =
                if (Jamendo.clientId.isNullOrBlank()) throw NotConfiguredException()
                else Jamendo.search(query, limit = pageSize, offset = offset)
            val items: List<YTItem> = tracks.map { it.toSongItem() }.filterExplicit(hideExplicit)
            val existing = viewStateMap[SearchFilterFree.value]?.items.orEmpty()
            // Only deduplicate exact id matches: "jamendo:<trackId>" is the provider's
            // own primary key, so this never merges genuinely different tracks.
            val seenIds = mutableSetOf<String>()
            val deduped = (existing + items).filter { seenIds.add(it.id) }
            val hasMore = tracks.size == pageSize
            viewStateMap[SearchFilterFree.value] =
                ItemsPage(deduped, if (hasMore) (offset + pageSize).toString() else null)
        } catch (e: Throwable) {
            freeSearchError = e
            if (viewStateMap[SearchFilterFree.value] == null) {
                viewStateMap[SearchFilterFree.value] = ItemsPage(emptyList(), null)
            }
            reportException(e)
        }
    }

    fun loadMore() {
        val filter = filter.value?.value
        viewModelScope.launch {
            when (filter) {
                null -> return@launch
                SearchFilterFree.value -> {
                    val viewState = viewStateMap[filter] ?: return@launch
                    val offset = viewState.continuation?.toIntOrNull() ?: return@launch
                    loadFreePage(offset)
                }

                SearchFilterYouTubeData.value -> {
                    val viewState = viewStateMap[filter] ?: return@launch
                    viewState.continuation?.takeIf { it.startsWith("ytdata:") }?.let { encoded ->
                        loadYouTubeDataPage(encoded.removePrefix("ytdata:"))
                    }
                }

                else -> {
                    val viewState = viewStateMap[filter] ?: return@launch
                    val continuation = viewState.continuation
                    if (continuation != null) {
                        val searchResult = YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
                        viewStateMap[filter] = ItemsPage((viewState.items + searchResult.items).distinctBy { it.id }, searchResult.continuation)
                    }
                }
            }
        }
    }

    /**
     * Results from the user's own Data API key. Cards keep the real YouTube video id
     * (no "jamendo:"-style mixing) and carry a WatchEndpoint so playback follows the
     * app's established compliant YouTube flow. Unknown durations stay unknown.
     */
    private suspend fun loadYouTubeDataPage(pageToken: String?) {
        try {
            youTubeDataSearchError = null
            val result = YouTubeData.search(query, pageToken = pageToken)
            val items: List<YTItem> = result.videos.map { it.toSongItem() }.filterExplicit(hideExplicit)
            val existing = viewStateMap[SearchFilterYouTubeData.value]?.items.orEmpty()
            val seenIds = mutableSetOf<String>()
            val deduped = (existing + items).filter { seenIds.add(it.id) }
            viewStateMap[SearchFilterYouTubeData.value] = ItemsPage(
                deduped,
                result.nextPageToken?.let { "ytdata:$it" },
            )
        } catch (e: Throwable) {
            youTubeDataSearchError = e
            if (viewStateMap[SearchFilterYouTubeData.value] == null) {
                viewStateMap[SearchFilterYouTubeData.value] = ItemsPage(emptyList(), null)
            }
            reportException(e)
        }
    }
}

/**
 * Map a Data API search hit to a search card. Duration is only shown when the API
 * actually advertises one (live streams and missing durations remain "unknown");
 * the channel title becomes the artist line.
 */
fun YouTubeDataVideo.toSongItem() = SongItem(
    id = videoId,
    title = title,
    artists = listOf(Artist(id = channelId, name = channelTitle ?: "")),
    duration = durationSeconds,
    thumbnail = thumbnail.orEmpty(),
    explicit = false,
    endpoint = WatchEndpoint(videoId = videoId),
)

/**
 * Map a Jamendo track to a shareable search card. The id is namespaced so the
 * player (and every downstream feature) can tell provider sources apart.
 */
fun JamendoTrack.toSongItem() = SongItem(
    id = "jamendo:$id",
    title = name,
    artists = listOf(Artist(id = null, name = artist_name)),
    // No provider album id exists; leaving album unset is the honest representation.
    album = null,
    duration = duration,
    thumbnail = image ?: album_image.orEmpty(),
    explicit = false,
    endpoint = null, // never a YouTube radio/playlist endpoint
)
