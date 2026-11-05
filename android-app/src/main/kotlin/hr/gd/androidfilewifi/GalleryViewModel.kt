package hr.gd.androidfilewifi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hr.gd.androidfilewifi.data.Album
import hr.gd.androidfilewifi.data.MediaFilter
import hr.gd.androidfilewifi.data.MediaItem
import hr.gd.androidfilewifi.data.MediaRepository
import hr.gd.androidfilewifi.data.toAlbums
import hr.gd.androidfilewifi.selection.SelectionController
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
        mediaSel.clear(); publishMedia()
        filter.value = MediaFilter.All
        openAlbumId.value = id
    }

    fun setFilter(f: MediaFilter) {
        filter.value = f
        // Keep only selected items that stay visible under the new filter.
        val albumId = openAlbumId.value
        val stillVisible = allItems.value.filter { item ->
            (albumId == null || albumId == ALL_ID || item.bucketId == albumId) &&
                when (f) {
                    MediaFilter.All -> true
                    MediaFilter.Photos -> !item.isVideo
                    MediaFilter.Videos -> item.isVideo
                }
        }.map { it.id }
        mediaSel.retainOnly(stillVisible)
        publishMedia()
    }

    // ---- Media selection -------------------------------------------------------------------
    private val mediaSel = SelectionController<Long>()
    val selectedMedia = MutableStateFlow<Set<Long>>(emptySet())
    val rangeMode = MutableStateFlow(false)

    private fun publishMedia() { selectedMedia.value = mediaSel.keys.toSet() }
    private fun displayedIds() = visibleItems.value.map { it.id }

    fun mediaLongPress(index: Int) { mediaSel.longPress(displayedIds(), index); publishMedia() }
    fun mediaTap(index: Int) { mediaSel.tap(displayedIds(), index); publishMedia() }
    fun mediaDrag(from: Int, to: Int) { mediaSel.addRange(displayedIds(), from, to); publishMedia() }
    fun toggleGroup(ids: List<Long>) { mediaSel.toggleGroup(ids); publishMedia() }
    fun selectAllMedia() { mediaSel.selectAll(displayedIds()); publishMedia() }
    fun clearMediaSelection() { mediaSel.clear(); publishMedia() }
    fun setRangeMode(on: Boolean) { rangeMode.value = on; mediaSel.rangeMode = on }

    fun selectedMediaItems(): List<MediaItem> {
        val ids = mediaSel.keys
        return visibleItems.value.filter { it.id in ids }
    }

    // ---- Folder selection ------------------------------------------------------------------
    private val albumSel = SelectionController<Long>()
    val selectedAlbums = MutableStateFlow<Set<Long>>(emptySet())
    private fun publishAlbums() { selectedAlbums.value = albumSel.keys.toSet() }
    private fun albumIds() = albums.value.map { it.bucketId }

    fun albumLongPress(index: Int) { albumSel.longPress(albumIds(), index); publishAlbums() }
    fun albumTap(index: Int) { albumSel.toggle(albumIds(), index); publishAlbums() }
    fun selectAllAlbums() { albumSel.selectAll(albumIds()); publishAlbums() }
    fun clearAlbumSelection() { albumSel.clear(); publishAlbums() }

    fun itemsInSelectedAlbums(): List<MediaItem> {
        val ids = albumSel.keys
        return allItems.value.filter { it.bucketId in ids }
    }

    // ---- Sending ---------------------------------------------------------------------------
    /** Items the user asked to send; the UI shows the send dialog while this is non-null. */
    val pendingSend = MutableStateFlow<List<MediaItem>?>(null)

    fun requestSend(items: List<MediaItem>) { if (items.isNotEmpty()) pendingSend.value = items }
    fun cancelSend() { pendingSend.value = null }

    /** Called after a send job was queued: leave selection mode and show the transfers list. */
    fun sendStarted() {
        pendingSend.value = null
        clearMediaSelection()
        clearAlbumSelection()
        overlay.value = Overlay.Transfers
    }

    // ---- Full-screen overlays on top of the gallery ---------------------------------------
    enum class Overlay { None, Transfers, Pcs }
    val overlay = MutableStateFlow(Overlay.None)
    fun openTransfers() { overlay.value = Overlay.Transfers }
    fun openPcs() { overlay.value = Overlay.Pcs }
    fun closeOverlay() { overlay.value = Overlay.None }

    fun openViewer(index: Int) { viewerIndex.value = index }
    fun closeViewer() { viewerIndex.value = null }

    /** Handles system Back. Returns true if it consumed the event. */
    fun onBack(): Boolean = when {
        pendingSend.value != null -> { pendingSend.value = null; true }
        overlay.value != Overlay.None -> { overlay.value = Overlay.None; true }
        viewerIndex.value != null -> { viewerIndex.value = null; true }
        mediaSel.isActive -> { clearMediaSelection(); true }
        albumSel.isActive -> { clearAlbumSelection(); true }
        openAlbumId.value != null -> { openAlbumId.value = null; true }
        else -> false
    }

    companion object {
        const val ALL_ID = Long.MIN_VALUE
    }
}
