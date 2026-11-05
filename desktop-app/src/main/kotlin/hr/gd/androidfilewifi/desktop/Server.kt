package hr.gd.androidfilewifi.desktop

import hr.gd.androidfilewifi.shared.CreateSessionRequest
import hr.gd.androidfilewifi.shared.ErrorResponse
import hr.gd.androidfilewifi.shared.PairRequest
import hr.gd.androidfilewifi.shared.PairResponse
import hr.gd.androidfilewifi.shared.PingResponse
import hr.gd.androidfilewifi.shared.Protocol
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.net.BindException
import java.security.MessageDigest

/** Everything the HTTP layer needs. */
class ReceiverContext(
    val config: ConfigStore,
    val identity: Identity,
    val pairing: PairingManager,
    val transfers: TransferManager,
    /** Called when a new phone pairs. */
    val onPaired: (PairedDevice) -> Unit = {},
)

private val apiJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun ReceiverContext.authenticate(call: ApplicationCall): PairedDevice {
    val header = call.request.headers["Authorization"].orEmpty()
    if (!header.startsWith("Bearer ")) throw ApiException(HttpStatusCode.Unauthorized, "Missing token")
    val hash = PairingManager.hashToken(header.removePrefix("Bearer ").trim()).toByteArray()
    return config.value.devices.firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), hash) }
        ?: throw ApiException(HttpStatusCode.Unauthorized, "Unknown or revoked device")
}

fun Application.receiverModule(ctx: ReceiverContext) {
    install(ContentNegotiation) { json(apiJson) }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, ErrorResponse(e.message ?: "Error", e.expectedOffset))
        }
        exception<Throwable> { call, e ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }

    routing {
        route(Protocol.API) {
            get("/ping") {
                call.respond(PingResponse(pcName = ctx.config.value.pcName))
            }

            post("/pair") {
                val req = call.receive<PairRequest>()
                if (req.deviceId.isBlank() || req.deviceName.isBlank()) {
                    throw ApiException(HttpStatusCode.BadRequest, "Missing device info")
                }
                if (!ctx.pairing.verify(req.code)) {
                    throw ApiException(HttpStatusCode.Forbidden, "Wrong pairing code")
                }
                val token = ctx.pairing.newToken()
                val device = PairedDevice(
                    id = req.deviceId.take(64),
                    name = req.deviceName.take(64),
                    tokenHash = PairingManager.hashToken(token),
                    pairedAt = System.currentTimeMillis(),
                )
                // Re-pairing the same phone replaces its old token.
                ctx.config.update { c -> c.copy(devices = c.devices.filter { it.id != device.id } + device) }
                ctx.onPaired(device)
                call.respond(PairResponse(token, ctx.config.value.pcName))
            }

            route("/sessions") {
                post {
                    val device = ctx.authenticate(call)
                    val req = call.receive<CreateSessionRequest>()
                    val (_, response) = ctx.transfers.create(device, req.files)
                    call.respond(response)
                }
                put("/{sid}/files/{id}") {
                    val device = ctx.authenticate(call)
                    val session = ctx.transfers.session(call.parameters["sid"].orEmpty(), device.id)
                    val fileId = call.parameters["id"]?.toIntOrNull()
                        ?: throw ApiException(HttpStatusCode.BadRequest, "Bad file id")
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                    call.respond(ctx.transfers.receive(session, fileId, offset, call.receiveChannel()))
                }
                get("/{sid}/files/{id}") {
                    val device = ctx.authenticate(call)
                    val session = ctx.transfers.session(call.parameters["sid"].orEmpty(), device.id)
                    val fileId = call.parameters["id"]?.toIntOrNull()
                        ?: throw ApiException(HttpStatusCode.BadRequest, "Bad file id")
                    call.respond(ctx.transfers.status(session, fileId))
                }
                post("/{sid}/complete") {
                    val device = ctx.authenticate(call)
                    val session = ctx.transfers.session(call.parameters["sid"].orEmpty(), device.id)
                    call.respond(ctx.transfers.complete(session))
                }
                delete("/{sid}") {
                    val device = ctx.authenticate(call)
                    val session = ctx.transfers.session(call.parameters["sid"].orEmpty(), device.id)
                    ctx.transfers.cancel(session)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

/** Starts the HTTPS receiver on the first free port in the configured range. */
class ReceiverServer(private val ctx: ReceiverContext) {
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    var port: Int = 0
        private set

    fun start(): Int {
        val first = ctx.config.value.preferredPort
        var lastError: Exception? = null
        for (p in first until first + Protocol.PORT_RANGE) {
            val server = embeddedServer(
                Netty,
                configure = {
                    sslConnector(
                        keyStore = ctx.identity.keyStore,
                        keyAlias = ctx.identity.alias,
                        keyStorePassword = { ctx.identity.password },
                        privateKeyPassword = { ctx.identity.password },
                    ) {
                        host = "0.0.0.0"
                        port = p
                    }
                },
            ) { receiverModule(ctx) }
            try {
                server.start(wait = false)
                engine = server
                port = p
                return p
            } catch (e: Exception) {
                runCatching { server.stop(0, 0) }
                lastError = e
                if (!isPortInUse(e)) throw e
            }
        }
        throw lastError ?: IllegalStateException("No free port")
    }

    fun stop() {
        engine?.stop(500, 1500)
        engine = null
    }

    private fun isPortInUse(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is BindException || it.message?.contains("in use", true) == true }
}
