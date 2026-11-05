package hr.gd.androidfilewifi.desktop

import hr.gd.androidfilewifi.shared.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    /** SHA-256 (hex) of the bearer token; the token itself is never stored. */
    val tokenHash: String,
    val pairedAt: Long,
)

@Serializable
data class Config(
    val receiveDir: String,
    val pcName: String,
    val preferredPort: Int = Protocol.DEFAULT_PORT,
    val devices: List<PairedDevice> = emptyList(),
    val autostart: Boolean = false,
    val closeToTray: Boolean = true,
)

/** Persists [Config] as JSON, writing atomically. */
class ConfigStore(private val dir: File) {
    private val file = File(dir, "config.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Config> = _state

    val value: Config get() = _state.value

    @Synchronized
    fun update(block: (Config) -> Config) {
        _state.value = block(_state.value)
        save(_state.value)
    }

    private fun load(): Config {
        runCatching {
            if (file.isFile) return json.decodeFromString(Config.serializer(), file.readText())
        }
        return defaults().also { save(it) }
    }

    private fun save(config: Config) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "config.json.tmp")
            tmp.writeText(json.encodeToString(Config.serializer(), config))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun defaultDir(): File {
            val appData = System.getenv("APPDATA")
            return if (appData != null) File(appData, "AndroidFileWifi")
            else File(System.getProperty("user.home"), ".androidfilewifi")
        }

        fun defaults(): Config {
            val pictures = File(System.getProperty("user.home"), "Pictures")
            val host = System.getenv("COMPUTERNAME")
                ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
                ?: "My PC"
            return Config(
                receiveDir = File(pictures, "AndroidFileWifi").absolutePath,
                pcName = host,
            )
        }
    }
}
