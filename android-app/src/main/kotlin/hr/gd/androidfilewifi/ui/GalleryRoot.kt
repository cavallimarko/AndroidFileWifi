package hr.gd.androidfilewifi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hr.gd.androidfilewifi.GalleryViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface

@Composable
fun GalleryRoot(
    viewModel: GalleryViewModel,
    partialAccess: Boolean,
    onAllowAll: () -> Unit,
) {
    val albumId by viewModel.openAlbumId.collectAsState()
    val viewerIndex by viewModel.viewerIndex.collectAsState()
    val visible by viewModel.visibleItems.collectAsState()
    val overlay by viewModel.overlay.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (partialAccess) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "You only allowed access to some photos.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onAllowAll) { Text("Allow all") }
                    }
                }
            }
            if (albumId == null) {
                AlbumsScreen(viewModel, topInset = !partialAccess, onOpenTransfers = viewModel::openTransfers, onOpenPc = viewModel::openPcs)
            } else {
                MediaGridScreen(viewModel, topInset = !partialAccess)
            }
        }

        Overlays(viewModel, overlay)

        viewerIndex?.let { index ->
            if (visible.isNotEmpty()) {
                ViewerScreen(
                    items = visible,
                    startIndex = index.coerceIn(0, visible.lastIndex),
                    onClose = viewModel::closeViewer,
                )
            }
        }
    }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Photos & videos", style = MaterialTheme.typography.headlineMedium)
        Text(
            "FileWifi needs access to your photos and videos so you can browse them and send them to your PC.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = onGrant) { Text("Grant access") }
    }
}

/** Transfers / PCs screens drawn over the gallery (also used when the gallery itself isn't available). */
@Composable
fun Overlays(viewModel: GalleryViewModel, overlay: GalleryViewModel.Overlay) {
    when (overlay) {
        GalleryViewModel.Overlay.Transfers -> Surface(Modifier.fillMaxSize()) { TransfersScreen(onBack = viewModel::closeOverlay) }
        GalleryViewModel.Overlay.Pcs -> Surface(Modifier.fillMaxSize()) { PcScreen(onBack = viewModel::closeOverlay) }
        GalleryViewModel.Overlay.None -> Unit
    }
}
