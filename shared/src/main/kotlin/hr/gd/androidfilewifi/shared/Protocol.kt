package hr.gd.androidfilewifi.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Constants shared by the Android sender and the desktop receiver. */
object Protocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 47800
    const val PORT_RANGE = 10
    const val SERVICE_TYPE = "_afw._tcp"
    const val API = "/api/v1"
}

@Serializable
data class PingResponse(val pcName: String, val version: Int = Protocol.VERSION)

@Serializable
data class PairRequest(val deviceId: String, val deviceName: String, val code: String)

@Serializable
data class PairResponse(val token: String, val pcName: String)

@Serializable
data class FileSpec(
    /** Sender-local id, unique within one session. */
    val id: Int,
    /** Relative path such as "Camera/IMG_0001.jpg". Always sanitized by the receiver. */
    val relPath: String,
    val size: Long,
    /** Last-modified / date-taken in epoch millis; applied to the received file. */
    val modified: Long,
    val mime: String = "",
)

@Serializable
data class CreateSessionRequest(val files: List<FileSpec>)

@Serializable
enum class FileStatus {
    @SerialName("new") New,
    @SerialName("skip") Skip,
    @SerialName("partial") Partial,
}

@Serializable
data class FileDecision(val id: Int, val status: FileStatus, val offset: Long = 0)

@Serializable
data class CreateSessionResponse(val sessionId: String, val files: List<FileDecision>)

@Serializable
data class UploadResponse(val received: Long, val done: Boolean)

@Serializable
data class CompleteResponse(val received: Int, val skipped: Int, val failed: Int)

@Serializable
data class ErrorResponse(val error: String, val expectedOffset: Long? = null)
