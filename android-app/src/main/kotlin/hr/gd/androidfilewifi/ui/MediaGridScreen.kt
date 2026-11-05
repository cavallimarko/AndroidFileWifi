package hr.gd.androidfilewifi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hr.gd.androidfilewifi.GalleryViewModel
import hr.gd.androidfilewifi.data.MediaFilter
import hr.gd.androidfilewifi.data.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private sealed interface GridEntry {
    data class Header(val key: String, val label: String) : GridEntry
    data class Tile(val item: MediaItem, val index: Int) : GridEntry
}

private fun buildEntries(items: List<MediaItem>): List<GridEntry> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val out = ArrayList<GridEntry>(items.size + 32)
    var lastDay: LocalDate? = null
    items.forEachIndexed { index, item ->
        val day = Instant.ofEpochMilli(item.dateMillis).atZone(zone).toLocalDate()
        if (day != lastDay) {
            lastDay = day
            val label = when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> day.format(formatter)
            }
            out += GridEntry.Header("h$day", label)
        }
        out += GridEntry.Tile(item, index)
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGridScreen(viewModel: GalleryViewModel, topInset: Boolean) {
    val albumId by viewModel.openAlbumId.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val items by viewModel.visibleItems.collectAsState()
    val filter by viewModel.filter.collectAsState()

    val title = if (albumId == GalleryViewModel.ALL_ID) "All photos & videos"
    else albums.firstOrNull { it.bucketId == albumId }?.name ?: ""
    val entries = remember(items) { buildEntries(items) }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = if (topInset) 120.dp + 24.dp else 112.dp,
                start = 2.dp, end = 2.dp, bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                entries,
                key = { if (it is GridEntry.Header) it.key else (it as GridEntry.Tile).item.id },
                span = { if (it is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { if (it is GridEntry.Header) "header" else "tile" },
            ) { entry ->
                when (entry) {
                    is GridEntry.Header -> Text(
                        entry.label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp),
                    )
                    is GridEntry.Tile -> MediaTile(entry.item) { viewModel.openViewer(entry.index) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.navigationBarsPadding().padding(16.dp))
            }
        }

        // Top bar with filter chips floats over the grid.
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                .then(if (topInset) Modifier.statusBarsPadding() else Modifier)
        ) {
            androidx.compose.foundation.layout.Column {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                )
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MediaFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { viewModel.filter.value = f },
                            label = { Text(f.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaTile(item: MediaItem, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.padding(end = 2.dp))
                Text(formatDuration(item.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
