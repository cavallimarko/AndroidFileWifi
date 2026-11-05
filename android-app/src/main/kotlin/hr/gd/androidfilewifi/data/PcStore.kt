package hr.gd.androidfilewifi.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class PairedPc(
    /** The certificate fingerprint: identifies the PC and is what we pin TLS to. */
    val id: String,
    val name: String,
    /** Last known address; refreshed through discovery when it stops answering. */
    val host: String,
    val port: Int,
    val token: String,
)

/** Paired PCs and this phone's identity, kept in app-private storage. */
class PcStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pcs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PairedPc.serializer())

    private val _pcs = MutableStateFlow(load())
    val pcs: StateFlow<List<PairedPc>> = _pcs

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

    val deviceName: String
        get() {
            val model = Build.MODEL.orEmpty().trim()
            val maker = Build.MANUFACTURER.orEmpty().trim()
            val full = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
            return full.trim().replaceFirstChar { it.uppercase() }.ifBlank { "Android phone" }
        }

    fun get(id: String): PairedPc? = _pcs.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(pc: PairedPc) {
        _pcs.value = _pcs.value.filter { it.id != pc.id } + pc
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        _pcs.value = _pcs.value.filter { it.id != id }
        persist()
    }

    @Synchronized
    fun updateAddress(id: String, host: String, port: Int) {
        val pc = get(id) ?: return
        if (pc.host == host && pc.port == port) return
        save(pc.copy(host = host, port = port))
    }

    private fun load(): List<PairedPc> = runCatching {
        json.decodeFromString(serializer, prefs.getString("pcs", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    private fun persist() {
        prefs.edit().putString("pcs", json.encodeToString(serializer, _pcs.value)).apply()
    }
}
