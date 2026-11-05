package hr.gd.androidfilewifi.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hr.gd.androidfilewifi.GalleryViewModel
import hr.gd.androidfilewifi.data.Album

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(
    viewModel: GalleryViewModel,
    topInset: Boolean,
    onOpenTransfers: () -> Unit,
    onOpenPc: () -> Unit,
) {
    val albums by viewModel.albums.collectAsState()
    val total by viewModel.totalCount.collectAsState()
    val newest by viewModel.newestItem.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val selected by viewModel.selectedAlbums.collectAsState()
    val selecting = selected.isNotEmpty()

    val all = newest?.let {
        Album(
            bucketId = GalleryViewModel.ALL_ID,
            name = "All photos & videos",
            cover = it,
            count = total,
            photoCount = 0,
            videoCount = 0,
        )
    }
    val tiles = listOfNotNull(all) + albums

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (topInset) Modifier.statusBarsPadding() else Modifier)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    IconButton(onClick = viewModel::clearAlbumSelection) {
                        Icon(Icons.Default.Close, contentDescription = "Clear selection")
                    }
                    Text(
                        "${selected.size} folder" + if (selected.size == 1) "" else "s",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::selectAllAlbums) {
                        Icon(Icons.Default.SelectAll, contentDescription = "Select all folders")
                    }
                    IconButton(onClick = { viewModel.requestSend(viewModel.itemsInSelectedAlbums()) }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to PC")
                    }
                } else {
                    Text(
                        "Folders",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    IconButton(onClick = onOpenTransfers) {
                        Icon(Icons.Default.SwapVert, contentDescription = "Transfers")
                    }
                    IconButton(onClick = onOpenPc) {
                        Icon(Icons.Default.Computer, contentDescription = "PCs")
                    }
                }
            }
        }
        items(tiles, key = { it.bucketId }) { album ->
            val albumIndex = albums.indexOfFirst { it.bucketId == album.bucketId }
            val isAll = album.bucketId == GalleryViewModel.ALL_ID
            AlbumTile(
                album = album,
                selected = album.bucketId in selected,
                selecting = selecting,
                onClick = {
                    when {
                        isAll -> viewModel.openAlbum(album.bucketId)
                        selecting -> viewModel.albumTap(albumIndex)
                        else -> viewModel.openAlbum(album.bucketId)
                    }
                },
                onLongClick = { if (!isAll) viewModel.albumLongPress(albumIndex) },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), Alignment.Center) {
                when {
                    loading && tiles.isEmpty() -> CircularProgressIndicator()
                    !loading && tiles.isEmpty() -> Text("No photos or videos found")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(
    album: Album,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box {
            AsyncImage(
                model = album.cover.uri,
                contentDescription = album.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (album.bucketId != GalleryViewModel.ALL_ID) {
                SelectionDot(
                    selected = selected,
                    selecting = selecting,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
        }
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            album.count.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
