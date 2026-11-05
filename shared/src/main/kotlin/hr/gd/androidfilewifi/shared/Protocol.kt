package hr.gd.androidfilewifi.shared

import kotlinx.serialization.Serializable

/** Constants and DTOs shared by the Android sender and the desktop receiver. */
object Protocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 47800
    const val SERVICE_TYPE = "_afw._tcp"
}

@Serializable
data class PingResponse(val pcName: String, val version: Int = Protocol.VERSION)
