package dev.brahmkshatriya.echo.ui.player.lyrics

import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.paging.PagingData
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.get
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.ui.common.PagingUtils.collectWith
import dev.brahmkshatriya.echo.ui.common.PagingUtils.toFlow
import dev.brahmkshatriya.echo.ui.extensions.list.ExtensionListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LyricsViewModel(
    private val app: App,
    extensionLoader: ExtensionLoader,
    playerState: PlayerState
) : ExtensionListViewModel<Extension<*>>() {

    private val currentMediaFlow = playerState.current
    private val extensions = extensionLoader.extensions
    override val extensionsFlow = MutableStateFlow(listOf<Extension<*>>())
    override val currentSelectionFlow = MutableStateFlow<Extension<*>?>(null)

    private suspend fun update() {
        val trackExtension = currentMediaFlow.value?.mediaItem?.extensionId?.let { id ->
            extensions.music.getExtension(id)?.takeIf { it.isClient<LyricsClient>() }
        }
        extensionsFlow.value =
            listOfNotNull(trackExtension) + extensions.lyrics.value.orEmpty()

        val id = app.settings.getString(LAST_LYRICS_KEY, null)
        val extension = extensionsFlow.getExtension(id) ?: trackExtension ?: return
        currentSelectionFlow.value = extension
        onExtensionSelected(extension)
    }

    val searchResults = MutableStateFlow<PagingData<Lyrics>?>(null)
    private suspend fun onSearch(query: String?): PagedData<Lyrics>? {
        val extension = currentSelectionFlow.value
        if (query == null) return null
        return extension?.get<LyricsSearchClient, PagedData<Lyrics>>(app.throwFlow) {
            searchLyrics(query)
        }
    }

    override fun onExtensionSelected(extension: Extension<*>) {
        searchResults.value = null
        app.settings.edit().putString(LAST_LYRICS_KEY, extension.id).apply()
        val streamableTrack = currentMediaFlow.value?.mediaItem ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val data = onTrackChange(extension, streamableTrack)
            if (data != null) {
                lyricsState.value = State.Loading
                lyricsState.value = extension.get<LyricsClient, Lyrics?>(app.throwFlow) {
                    val unloaded = data.loadFirst().firstOrNull() ?: return@get null
                    loadLyrics(unloaded)
                }?.let { State.Loaded(it) } ?: State.Empty
                data.toFlow(extension).collectWith(app.throwFlow, searchResults)
            }
        }
    }

    private suspend fun onTrackChange(
        extension: Extension<*>,
        mediaItem: MediaItem
    ): PagedData<Lyrics>? {
        val track = mediaItem.track
        return extension.get<LyricsClient, PagedData<Lyrics>>(app.throwFlow) {
            searchTrackLyrics(mediaItem.extensionId, track)
        }
    }

    fun search(query: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val extension = currentSelectionFlow.value ?: return@launch
            val data = onSearch(query)
            if (data != null) data.toFlow(extension).collectWith(app.throwFlow, searchResults)
            else searchResults.value = null
        }
    }

    sealed interface State {
        data object Loading : State
        data object Empty : State
        data class Loaded(val lyrics: Lyrics) : State
    }

    val lyricsState = MutableStateFlow<State>(State.Empty)
    fun onLyricsSelected(lyricsItem: Lyrics) {
        val extension = currentSelectionFlow.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            lyricsState.value = State.Loading
            lyricsState.value = extension.get<LyricsClient, Lyrics>(app.throwFlow) {
                loadLyrics(lyricsItem)
            }?.fillGaps()?.let { State.Loaded(it) } ?: State.Empty
        }
    }

    private fun Lyrics.fillGaps(): Lyrics {
        val lyrics = this.lyrics as? Lyrics.Timed
        return if (lyrics != null && lyrics.fillTimeGaps) {
            val new = mutableListOf<Lyrics.Item>()
            var last = 0L
            lyrics.list.forEach {
                if (it.startTime > last) {
                    new.add(Lyrics.Item("", last, it.startTime))
                }
                new.add(it)
                last = it.endTime
            }
            this.copy(lyrics = Lyrics.Timed(new))
        } else this
    }

    init {
        viewModelScope.launch {
            update()
            currentMediaFlow.map { it?.mediaItem }.distinctUntilChanged().collect {
                update()
            }
        }
    }

    companion object {
        const val LAST_LYRICS_KEY = "last_lyrics_client"
    }
}