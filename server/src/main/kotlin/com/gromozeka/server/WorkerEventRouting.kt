package com.gromozeka.server

import com.gromozeka.application.service.ContextStateApplicationService
import com.gromozeka.application.service.WorkerContactApplicationService
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.WorkerAppState
import com.gromozeka.domain.model.WorkerContactKind
import com.gromozeka.remote.protocol.WorkerEventBatchRequest
import com.gromozeka.remote.protocol.WorkerEventBatchResponse
import com.gromozeka.remote.protocol.WorkerHeartbeatRequest
import com.gromozeka.remote.protocol.WorkerHeartbeatResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val workerEventLog = KLoggers.logger("WorkerEventRouting")

internal fun Routing.gromozekaWorkerEvents(
    authenticationService: WorkerGatewayAuthenticationService,
    contextStateService: ContextStateApplicationService,
    contactService: WorkerContactApplicationService,
) {
    post("/api/worker/events") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val authenticated = call.authenticateWorkerEvent(authenticationService) ?: return@post
        val request = call.receiveWorkerEventRequest<WorkerEventBatchRequest>(
            invalidMessage = "Invalid Worker event batch",
            unreadableMessage = "Worker event batch could not be read",
            oversizedMessage = "Worker event batch is too large",
        ) ?: return@post
        try {
            val result = contextStateService.ingestWorker(
                worker = authenticated.worker,
                observations = request.events.map { it.toObservation() },
            )
            val contact = request.contact
            contactService.record(
                worker = authenticated.worker,
                requestId = contact?.requestId ?: request.events.first().id,
                kind = WorkerContactKind.EVENT_BATCH,
                appState = contact?.appState ?: WorkerAppState.UNKNOWN,
                appVersion = contact?.appVersion ?: request.deviceInfoVersion(),
                workerSentAt = contact?.sentAt,
                eventCount = request.events.size,
                pendingEventCount = contact?.pendingEventCount,
                receivedAt = result.receivedAt,
            )
            call.respondText(
                workerEventJson.encodeToString(
                    WorkerEventBatchResponse(
                        acceptedEventIds = result.acceptedIds,
                        duplicateEventIds = result.duplicateIds,
                        serverReceivedAt = result.receivedAt,
                    )
                ),
                ContentType.Application.Json,
            )
        } catch (error: IllegalArgumentException) {
            call.respondWorkerEventError(
                error.message ?: "Worker synchronization failed",
                HttpStatusCode.BadRequest,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            workerEventLog.error {
                "Worker synchronization failed: worker=${authenticated.worker.id.value} error=${error::class.simpleName}"
            }
            call.respondWorkerEventError(
                "Worker synchronization failed",
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/api/worker/heartbeat") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val authenticated = call.authenticateWorkerEvent(authenticationService) ?: return@post
        val request = call.receiveWorkerEventRequest<WorkerHeartbeatRequest>(
            invalidMessage = "Invalid Worker heartbeat",
            unreadableMessage = "Worker heartbeat could not be read",
            oversizedMessage = "Worker heartbeat is too large",
        ) ?: return@post
        try {
            val receivedAt = contactService.record(
                worker = authenticated.worker,
                requestId = request.contact.requestId,
                kind = WorkerContactKind.HEARTBEAT,
                appState = request.contact.appState,
                appVersion = request.contact.appVersion,
                workerSentAt = request.contact.sentAt,
                eventCount = 0,
                pendingEventCount = request.contact.pendingEventCount,
            )
            call.respondText(
                workerEventJson.encodeToString(WorkerHeartbeatResponse(receivedAt)),
                ContentType.Application.Json,
            )
        } catch (error: IllegalArgumentException) {
            call.respondWorkerEventError(
                error.message ?: "Worker heartbeat failed",
                HttpStatusCode.BadRequest,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            workerEventLog.error {
                "Worker heartbeat failed: worker=${authenticated.worker.id.value} error=${error::class.simpleName}"
            }
            call.respondWorkerEventError(
                "Worker heartbeat failed",
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.authenticateWorkerEvent(
    authenticationService: WorkerGatewayAuthenticationService,
): AuthenticatedWorkerGateway? {
    if (!isSecureTransport()) {
        respondWorkerEventError("Worker synchronization requires HTTPS", HttpStatusCode.UpgradeRequired)
        return null
    }
    val authenticated = authenticationService.authenticate(request.header(HttpHeaders.Authorization))
    if (authenticated == null) {
        respondWorkerEventError("Worker authentication required", HttpStatusCode.Unauthorized)
        return null
    }
    if (authenticated.worker.subjectUserId == null) {
        respondWorkerEventError("Worker is not bound to a user for context reporting", HttpStatusCode.Forbidden)
        return null
    }
    return authenticated
}

private suspend inline fun <reified T> io.ktor.server.application.ApplicationCall.receiveWorkerEventRequest(
    invalidMessage: String,
    unreadableMessage: String,
    oversizedMessage: String,
): T? {
    val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (contentLength != null && contentLength > MAX_WORKER_EVENT_REQUEST_BYTES) {
        respondWorkerEventError(oversizedMessage, HttpStatusCode.PayloadTooLarge)
        return null
    }
    val requestBytes = try {
        receiveChannel().readRemaining((MAX_WORKER_EVENT_REQUEST_BYTES + 1).toLong()).readByteArray()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        respondWorkerEventError(unreadableMessage, HttpStatusCode.BadRequest)
        return null
    }
    if (requestBytes.size > MAX_WORKER_EVENT_REQUEST_BYTES) {
        respondWorkerEventError(oversizedMessage, HttpStatusCode.PayloadTooLarge)
        return null
    }
    return try {
        workerEventJson.decodeFromString<T>(requestBytes.decodeToString())
    } catch (error: SerializationException) {
        respondWorkerEventError(invalidMessage, HttpStatusCode.BadRequest)
        null
    } catch (error: IllegalArgumentException) {
        respondWorkerEventError(invalidMessage, HttpStatusCode.BadRequest)
        null
    }
}

private fun WorkerEventBatchRequest.deviceInfoVersion(): String? =
    events.asSequence()
        .mapNotNull { (it.payload as? DeviceStateEvent.DeviceInfo)?.appVersion }
        .firstOrNull()

private suspend fun io.ktor.server.application.ApplicationCall.respondWorkerEventError(
    message: String,
    status: HttpStatusCode,
) {
    respondText(
        workerEventJson.encodeToString(WorkerEventError(message)),
        ContentType.Application.Json,
        status,
    )
}

@Serializable
private data class WorkerEventError(val error: String)

private val workerEventJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

private const val MAX_WORKER_EVENT_REQUEST_BYTES = 256 * 1024
