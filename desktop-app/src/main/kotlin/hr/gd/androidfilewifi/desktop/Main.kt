package hr.gd.androidfilewifi.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.io.RandomAccessFile
import javax.swing.UIManager
import kotlin.system.exitProcess

private val LightColors = lightColorScheme(primary = Color(0xFF1E6FD9))
private val DarkColors = darkColorScheme(primary = Color(0xFF8AB4F8))

/** Only one receiver may run per user; a second launch would just fight over the port. */
private fun acquireSingleInstanceLock(): Boolean {
    val dir = ConfigStore.defaultDir().apply { mkdirs() }
    val channel = RandomAccessFile(File(dir, "app.lock"), "rw").channel
    val lock = runCatching { channel.tryLock() }.getOrNull()
    // keep the channel (and therefore the lock) referenced for the life of the process
    lockHolder = channel
    return lock != null
}

@Volatile private var lockHolder: Any? = null

fun main(args: Array<String>) {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    if (!acquireSingleInstanceLock()) exitProcess(0)
    val startMinimized = args.contains("--minimized")

    application {
        val scope = rememberCoroutineScope()
        val trayState = rememberTrayState()
        val controller = remember {
            AppController(scope, notify = { title, msg ->
                trayState.sendNotification(Notification(title, msg))
            }).also { it.start() }
        }
        val config by controller.config.state.collectAsState()
        var windowVisible by remember { mutableStateOf(!startMinimized) }

        val appIcon = remember { BitmapPainter(AppIcon.render(256).toComposeImageBitmap()) }
        val trayIcon = remember { BitmapPainter(AppIcon.render(64).toComposeImageBitmap()) }

        fun quit() {
            controller.stop()
            exitApplication()
        }

        Tray(
            icon = trayIcon,
            state = trayState,
            tooltip = "AndroidFileWifi",
            onAction = { windowVisible = true },
            menu = {
                Item("Open AndroidFileWifi", onClick = { windowVisible = true })
                Item("Open receive folder", onClick = { controller.openFolder(File(config.receiveDir)) })
                Separator()
                Item("Exit", onClick = ::quit)
            },
        )

        Window(
            visible = windowVisible,
            onCloseRequest = { if (config.closeToTray) windowVisible = false else quit() },
            title = "AndroidFileWifi",
            icon = appIcon,
            state = rememberWindowState(size = DpSize(820.dp, 860.dp)),
        ) {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
                MainScreen(controller)
            }
        }
    }
}
