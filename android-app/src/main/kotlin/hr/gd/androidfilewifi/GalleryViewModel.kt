package hr.gd.androidfilewifi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hr.gd.androidfilewifi.data.Album
import hr.gd.androidfilewifi.data.MediaFilter
import hr.gd.androidfilewifi.data.MediaItem
import hr.gd.androidfilewifi.data.MediaRepository
import hr.gd.androidfilewifi.data.toAlbums
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)

    private val allItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val loading = MutableStateFlow(false)

    /** Real folders, plus nothing else; the "All" pseudo-folder is added by the UI. */
    val albums: StateFlow<List<Album>> = allItems
        .map { it.toAlbums() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalCount: StateFlow<Int> = allItems
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val newestItem: StateFlow<MediaItem?> = allItems
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** null = folder list is showing; [ALL_ID] = every item; otherwise a MediaStore bucket id. */
    val openAlbumId = MutableStateFlow<Long?>(null)
    val filter = MutableStateFlow(MediaFilter.All)

    /** Index into [visibleItems] of the item shown in the viewer, or null. */
    val viewerIndex = MutableStateFlow<Int?>(null)

    val visibleItems: StateFlow<List<MediaItem>> =
        combine(allItems, openAlbumId, filter) { items, albumId, filter ->
            items.filter { item ->
                (albumId == null || albumId == ALL_ID || item.bucketId == albumId) &&
                    when (filter) {
                        MediaFilter.All -> true
                        MediaFilter.Photos -> !item.isVideo
                        MediaFilter.Videos -> item.isVideo
                    }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            try {
                allItems.value = repo.loadAll()
            } finally {
                loading.value = false
            }
        }
    }

    fun openAlbum(id: Long) {
        filter.value = MediaFilter.All
        openAlbumId.value = id
    }

    fun openViewer(index: Int) { viewerIndex.value = index }
    fun closeViewer() { viewerIndex.value = null }

    /** Handles system Back. Returns true if it consumed the event. */
    fun onBack(): Boolean = when {
        viewerIndex.value != null -> { viewerIndex.value = null; true }
        openAlbumId.value != null -> { openAlbumId.value = null; true }
        else -> false
    }

    companion object {
        const val ALL_ID = Long.MIN_VALUE
    }
}
