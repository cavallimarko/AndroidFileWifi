package hr.gd.androidfilewifi.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hr.gd.androidfilewifi.GalleryViewModel
import hr.gd.androidfilewifi.data.MediaFilter
import hr.gd.androidfilewifi.data.MediaItem
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private sealed interface GridEntry {
    data class Header(val key: String, val label: String, val ids: List<Long>) : GridEntry
    data class Tile(val item: MediaItem, val index: Int) : GridEntry
}

private fun buildEntries(items: List<MediaItem>): List<GridEntry> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val days = LinkedHashMap<LocalDate, MutableList<Pair<MediaItem, Int>>>()
    items.forEachIndexed { index, item ->
        val day = Instant.ofEpochMilli(item.dateMillis).atZone(zone).toLocalDate()
        days.getOrPut(day) { ArrayList() } += item to index
    }
    val out = ArrayList<GridEntry>(items.size + days.size)
    for ((day, list) in days) {
        val label = when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(formatter)
        }
        out += GridEntry.Header("h$day", label, list.map { it.first.id })
        list.forEach { (item, index) -> out += GridEntry.Tile(item, index) }
    }
    return out
}

private class DragState {
    var start = -1
    var last = -1
    var pointer = Offset.Zero
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGridScreen(viewModel: GalleryViewModel, topInset: Boolean) {
    val albumId by viewModel.openAlbumId.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val items by viewModel.visibleItems.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val selected by viewModel.selectedMedia.collectAsState()
    val rangeMode by viewModel.rangeMode.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current

    val title = if (albumId == GalleryViewModel.ALL_ID) "All photos & videos"
    else albums.firstOrNull { it.bucketId == albumId }?.name ?: ""
    val entries = remember(items) { buildEntries(items) }
    val entryByKey = remember(entries) {
        entries.associateBy { if (it is GridEntry.Header) it.key else (it as GridEntry.Tile).item.id }
    }
    val gridState = rememberLazyGridState()
    val selecting = selected.isNotEmpty()

    fun entryAt(offset: Offset): GridEntry? {
        val x = offset.x.toInt()
        val y = offset.y.toInt()
        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
            x >= it.offset.x && x < it.offset.x + it.size.width &&
                y >= it.offset.y && y < it.offset.y + it.size.height
        } ?: return null
        return entryByKey[info.key]
    }

    val drag = remember { DragState() }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    var barHeightPx by remember { mutableIntStateOf(0) }

    fun extendDrag() {
        val e = entryAt(drag.pointer)
        if (e is GridEntry.Tile && drag.start >= 0 && e.index != drag.last) {
            drag.last = e.index
            viewModel.mediaDrag(drag.start, e.index)
        }
    }

    // Auto-scroll while dragging near the top / bottom edge.
    LaunchedEffect(autoScroll) {
        while (autoScroll != 0f) {
            gridState.scrollBy(autoScroll)
            extendDrag()
            delay(16)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(entries) {
                    detectTapGestures(onTap = { off ->
                        when (val e = entryAt(off)) {
                            is GridEntry.Header -> viewModel.toggleGroup(e.ids)
                            is GridEntry.Tile ->
                                if (viewModel.selectedMedia.value.isNotEmpty()) viewModel.mediaTap(e.index)
                                else viewModel.openViewer(e.index)
                            null -> Unit
                        }
                    })
                }
                .pointerInput(entries) {
                    val edge = 96.dp.toPx()
                    detectDragGesturesAfterLongPress(
                        onDragStart = { off ->
                            drag.pointer = off
                            val e = entryAt(off)
                            if (e is GridEntry.Tile) {
                                drag.start = e.index
                                drag.last = e.index
                                viewModel.mediaLongPress(e.index)
                            } else {
                                drag.start = -1
                            }
                        },
                        onDrag = { change, _ ->
                            drag.pointer = change.position
                            extendDrag()
                            val h = size.height
                            autoScroll = when {
                                change.position.y < edge -> -18f
                                change.position.y > h - edge -> 18f
                                else -> 0f
                            }
                        },
                        onDragEnd = { autoScroll = 0f; drag.start = -1 },
                        onDragCancel = { autoScroll = 0f; drag.start = -1 },
                    )
                },
            contentPadding = PaddingValues(
                top = with(density) { barHeightPx.toDp() } + 4.dp,
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
                    is GridEntry.Header -> {
                        val all = entry.ids.all { it in selected }
                        Row(
                            Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(entry.label, style = MaterialTheme.typography.titleSmall)
                            if (selecting) {
                                Spacer(Modifier.size(8.dp))
                                SelectionDot(selected = all, selecting = true)
                            }
                        }
                    }
                    is GridEntry.Tile -> MediaTile(
                        entry.item,
                        selected = entry.item.id in selected,
                        selecting = selecting,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.navigationBarsPadding().padding(16.dp))
            }
        }

        // Top bar floats over the grid.
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { barHeightPx = it.height }
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
                .then(if (topInset) Modifier.statusBarsPadding() else Modifier)
        ) {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearMediaSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::selectAllMedia) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = { shareItems(context, viewModel.selectedMediaItems()) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { viewModel.requestSend(viewModel.selectedMediaItems()) }) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to PC")
                        }
                    },
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                )
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = rangeMode,
                        onClick = { viewModel.setRangeMode(!rangeMode) },
                        label = { Text("Range") },
                    )
                    Text(
                        if (rangeMode) "Tap the last item to select everything in between"
                        else "Long-press another item to select a range",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
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
                            onClick = { viewModel.setFilter(f) },
                            label = { Text(f.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SelectionDot(selected: Boolean, selecting: Boolean, modifier: Modifier = Modifier) {
    if (!selecting) return
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier
                    .background(Color.Black.copy(alpha = 0.25f))
                    .border(1.5.dp, Color.White, CircleShape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check, null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun MediaTile(item: MediaItem, selected: Boolean, selecting: Boolean) {
    val scale by animateFloatAsState(if (selected) 0.86f else 1f, label = "tileScale")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .scale(scale)
                .clip(RoundedCornerShape(if (selected) 6.dp else 2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
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
                    Icon(
                        Icons.Default.PlayArrow, null, tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        formatDuration(item.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        SelectionDot(
            selected = selected,
            selecting = selecting,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        )
    }
}

internal fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
