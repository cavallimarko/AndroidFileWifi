package hr.gd.androidfilewifi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.collectAsState
import hr.gd.androidfilewifi.share.SharedIntent
import hr.gd.androidfilewifi.ui.GalleryRoot
import hr.gd.androidfilewifi.ui.Overlays
import hr.gd.androidfilewifi.ui.PermissionScreen
import hr.gd.androidfilewifi.ui.SendHost

enum class MediaAccess { None, Partial, Full }

private fun requiredPermissions(): Array<String> {
    val base = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    // Lets us send photos with their location data intact (Android hides it from apps otherwise).
    return if (Build.VERSION.SDK_INT >= 29) base + Manifest.permission.ACCESS_MEDIA_LOCATION else base
}

private fun android.content.Context.mediaAccess(): MediaAccess {
    fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 33 -> when {
            granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.Full
            Build.VERSION.SDK_INT >= 34 &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Partial
            else -> MediaAccess.None
        }
        granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.Full
        else -> MediaAccess.None
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: GalleryViewModel by viewModels()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Handles "Send to PC" from the share sheet, and taps on transfer notifications. */
    private fun handleIntent(intent: android.content.Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_OPEN_TRANSFERS, false)) viewModel.openTransfers()
        val shared = SharedIntent.parse(this, intent)
        if (shared.isNotEmpty()) viewModel.requestSend(shared)
    }

    companion object {
        const val EXTRA_OPEN_TRANSFERS = "open_transfers"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val context = LocalContext.current
                    var access by remember { mutableStateOf(context.mediaAccess()) }

                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        access = context.mediaAccess()
                        if (access != MediaAccess.None) viewModel.refresh()
                    }

                    // Re-check access and reload whenever we come back to the foreground
                    // (the user may have changed permissions or added photos).
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_START) {
                                access = context.mediaAccess()
                                if (access != MediaAccess.None) viewModel.refresh()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    BackHandler { if (!viewModel.onBack()) finish() }
                    if (access == MediaAccess.None) {
                        val overlay by viewModel.overlay.collectAsState()
                        PermissionScreen(onGrant = { launcher.launch(requiredPermissions()) })
                        Overlays(viewModel, overlay)
                    } else {
                        GalleryRoot(
                            viewModel = viewModel,
                            partialAccess = access == MediaAccess.Partial,
                            onAllowAll = { launcher.launch(requiredPermissions()) },
                        )
                    }
                    // Works with or without gallery access, so the share sheet can send straight away.
                    SendHost(viewModel)
                }
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
