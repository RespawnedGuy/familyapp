package familyapp.sync.relay

import familyapp.sync.protocol.AckRequest
import familyapp.sync.protocol.AddDeviceRequest
import familyapp.sync.protocol.CancelTimerRequest
import familyapp.sync.protocol.CreateFamilyRequest
import familyapp.sync.protocol.DeviceIdentity
import familyapp.sync.protocol.EnvelopeUpload
import familyapp.sync.protocol.Hashing
import familyapp.sync.protocol.LiveEvent
import familyapp.sync.protocol.PullResponse
import familyapp.sync.protocol.RequestSigning
import familyapp.sync.protocol.TimerRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }
private const val MAX_BODY_BYTES = 1_000_000

/** HTTP and WebSocket API of the blind relay. */
fun Application.relayModule(store: RelayStore, clock: () -> Long = System::currentTimeMillis) {
    val verifier = RequestVerifier(store, clock)
    val live = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 64)

    install(WebSockets)

    routing {
        get("/health") { call.respondText("ok") }

        post("/v1/families") {
            val request = json.decodeFromString<CreateFamilyRequest>(call.receive<String>())
            if (store.familyExists(request.familyId)) return@post call.respondText("family exists", status = HttpStatusCode.Conflict)
            store.addDevice(request.familyId, DeviceIdentity.deviceIdOf(request.publicKey), request.publicKey)
            call.respondText("created", status = HttpStatusCode.Created)
        }

        route("/v1/families/{familyId}") {
            post("/devices") {
                val familyId = call.parameters["familyId"]!!
                val request = json.decodeFromString<AddDeviceRequest>(call.receive<String>())
                val endorserKey = store.publicKey(familyId, request.endorsedBy)
                val valid = endorserKey != null && DeviceIdentity.verify(
                    endorserKey, RequestSigning.endorsement(familyId, request.publicKey), request.endorsement,
                )
                if (!valid) return@post call.respondText("endorsement invalid", status = HttpStatusCode.Forbidden)
                store.addDevice(familyId, DeviceIdentity.deviceIdOf(request.publicKey), request.publicKey)
                call.respondText("added", status = HttpStatusCode.Created)
            }

            post("/envelopes") {
                authenticated(verifier) { familyId, deviceId, body ->
                    val upload = json.decodeFromString<EnvelopeUpload>(body.decodeToString())
                    val seq = store.appendEnvelope(familyId, upload.id, deviceId, upload.ciphertext)
                    live.tryEmit(familyId to seq)
                    call.respondText(seq.toString(), status = HttpStatusCode.Created)
                }
            }

            get("/envelopes") {
                authenticated(verifier) { familyId, _, _ ->
                    val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
                    val response = PullResponse(store.envelopesAfter(familyId, after), store.latestSeq(familyId))
                    call.respondText(json.encodeToString(response), ContentType.Application.Json)
                }
            }

            post("/ack") {
                authenticated(verifier) { familyId, deviceId, body ->
                    store.acknowledge(familyId, deviceId, json.decodeFromString<AckRequest>(body.decodeToString()).seq)
                    call.respondText("ok")
                }
            }

            post("/timers") {
                authenticated(verifier) { familyId, _, body ->
                    val timer = json.decodeFromString<TimerRequest>(body.decodeToString())
                    if (!store.deviceIds(familyId).containsAll(timer.recipients)) {
                        return@authenticated call.respondText("unknown recipient", status = HttpStatusCode.BadRequest)
                    }
                    store.scheduleTimer(familyId, timer)
                    call.respondText("scheduled", status = HttpStatusCode.Created)
                }
            }

            post("/timers/{timerId}/cancel") {
                authenticated(verifier) { familyId, _, body ->
                    val token = json.decodeFromString<CancelTimerRequest>(body.decodeToString()).cancelToken
                    val cancelled = store.cancelTimer(familyId, call.parameters["timerId"]!!, Hashing.sha256Hex(token.toByteArray()))
                    if (cancelled) call.respondText("cancelled") else call.respondText("not cancellable", status = HttpStatusCode.Conflict)
                }
            }

            webSocket("/live") {
                val familyId = call.parameters["familyId"]!!
                if (verifier.verify(call, familyId, ByteArray(0)) == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                    return@webSocket
                }
                live.filter { it.first == familyId }.collect { (_, seq) ->
                    send(Frame.Text(json.encodeToString(LiveEvent(seq))))
                }
            }
        }
    }
}

private suspend fun RoutingContext.authenticated(
    verifier: RequestVerifier,
    block: suspend RoutingContext.(familyId: String, deviceId: String, body: ByteArray) -> Unit,
) {
    val familyId = call.parameters["familyId"]!!
    val body = call.receive<ByteArray>()
    if (body.size > MAX_BODY_BYTES) return call.respondText("too large", status = HttpStatusCode.PayloadTooLarge)
    val deviceId = verifier.verify(call, familyId, body)
        ?: return call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
    block(familyId, deviceId, body)
}
