package hr.gd.androidfilewifi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.foundation.layout.navigationBarsPadding
import coil3.compose.AsyncImage
import hr.gd.androidfilewifi.GalleryViewModel
import hr.gd.androidfilewifi.data.Album

@Composable
fun AlbumsScreen(viewModel: GalleryViewModel, topInset: Boolean) {
    val albums by viewModel.albums.collectAsState()
    val total by viewModel.totalCount.collectAsState()
    val newest by viewModel.newestItem.collectAsState()
    val loading by viewModel.loading.collectAsState()

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

    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Folders",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .then(if (topInset) Modifier.statusBarsPadding() else Modifier)
                        .padding(vertical = 8.dp),
                )
            }
            items(tiles, key = { it.bucketId }) { album ->
                AlbumTile(album) { viewModel.openAlbum(album.bucketId) }
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
}

@Composable
private fun AlbumTile(album: Album, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = album.cover.uri,
            contentDescription = album.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
        )
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
