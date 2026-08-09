package com.gromozeka.server

import com.gromozeka.application.service.ContextStateApplicationService
import com.gromozeka.application.service.MobileWorkerContactApplicationService
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.MobileWorkerAppState
import com.gromozeka.domain.model.MobileWorkerContactKind
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.remote.protocol.MobileWorkerEventBatchRequest
import com.gromozeka.remote.protocol.MobileWorkerEventBatchResponse
import com.gromozeka.remote.protocol.MobileWorkerHeartbeatRequest
import com.gromozeka.remote.protocol.MobileWorkerHeartbeatResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val mobileWorkerLog = KLoggers.logger("MobileWorkerRouting")

internal fun Routing.gromozekaMobileWorkers(
    authenticationService: WorkerGatewayAuthenticationService,
    contextStateService: ContextStateApplicationService,
    contactService: MobileWorkerContactApplicationService,
) {
    post("/api/mobile-worker/events") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val authenticated = call.authenticateMobileWorker(authenticationService) ?: return@post
        val request = call.receiveMobileWorkerRequest<MobileWorkerEventBatchRequest>(
            invalidMessage = "Invalid Mobile Worker event batch",
            unreadableMessage = "Mobile Worker event batch could not be read",
            oversizedMessage = "Mobile Worker event batch is too large",
        ) ?: return@post
        try {
            val result = contextStateService.ingestMobileWorker(
                worker = authenticated.worker,
                observations = request.events.map { it.toObservation() },
            )
            val contact = request.contact
            contactService.record(
                worker = authenticated.worker,
                requestId = contact?.requestId ?: request.events.first().id,
                kind = MobileWorkerContactKind.EVENT_BATCH,
                appState = contact?.appState ?: MobileWorkerAppState.UNKNOWN,
                appVersion = contact?.appVersion ?: request.deviceInfoVersion(),
                workerSentAt = contact?.sentAt,
                eventCount = request.events.size,
                pendingEventCount = contact?.pendingEventCount,
                receivedAt = result.receivedAt,
            )
            call.respondText(
                mobileWorkerJson.encodeToString(
                    MobileWorkerEventBatchResponse(
                        acceptedEventIds = result.acceptedIds,
                        duplicateEventIds = result.duplicateIds,
                        serverReceivedAt = result.receivedAt,
                    )
                ),
                ContentType.Application.Json,
            )
        } catch (error: IllegalArgumentException) {
            call.respondMobileWorkerError(
                error.message ?: "Mobile Worker synchronization failed",
                HttpStatusCode.BadRequest,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            mobileWorkerLog.error(error) {
                "Mobile Worker synchronization failed: worker=${authenticated.worker.id.value} error=${error.message}"
            }
            call.respondMobileWorkerError(
                "Mobile Worker synchronization failed",
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/api/mobile-worker/heartbeat") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val authenticated = call.authenticateMobileWorker(authenticationService) ?: return@post
        val request = call.receiveMobileWorkerRequest<MobileWorkerHeartbeatRequest>(
            invalidMessage = "Invalid Mobile Worker heartbeat",
            unreadableMessage = "Mobile Worker heartbeat could not be read",
            oversizedMessage = "Mobile Worker heartbeat is too large",
        ) ?: return@post
        try {
            val receivedAt = contactService.record(
                worker = authenticated.worker,
                requestId = request.contact.requestId,
                kind = MobileWorkerContactKind.HEARTBEAT,
                appState = request.contact.appState,
                appVersion = request.contact.appVersion,
                workerSentAt = request.contact.sentAt,
                eventCount = 0,
                pendingEventCount = request.contact.pendingEventCount,
            )
            call.respondText(
                mobileWorkerJson.encodeToString(MobileWorkerHeartbeatResponse(receivedAt)),
                ContentType.Application.Json,
            )
        } catch (error: IllegalArgumentException) {
            call.respondMobileWorkerError(
                error.message ?: "Mobile Worker heartbeat failed",
                HttpStatusCode.BadRequest,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            mobileWorkerLog.error(error) {
                "Mobile Worker heartbeat failed: worker=${authenticated.worker.id.value} error=${error.message}"
            }
            call.respondMobileWorkerError(
                "Mobile Worker heartbeat failed",
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.authenticateMobileWorker(
    authenticationService: WorkerGatewayAuthenticationService,
): AuthenticatedWorkerGateway? {
    if (!isSecureTransport()) {
        respondMobileWorkerError("Mobile Worker synchronization requires HTTPS", HttpStatusCode.UpgradeRequired)
        return null
    }
    val authenticated = authenticationService.authenticate(request.header(HttpHeaders.Authorization))
    if (authenticated == null) {
        respondMobileWorkerError("Mobile Worker authentication required", HttpStatusCode.Unauthorized)
        return null
    }
    if (authenticated.worker.kind != WorkerResource.Kind.MOBILE_DEVICE) {
        respondMobileWorkerError("Credential does not belong to a mobile device Worker", HttpStatusCode.Forbidden)
        return null
    }
    return authenticated
}

private suspend inline fun <reified T> io.ktor.server.application.ApplicationCall.receiveMobileWorkerRequest(
    invalidMessage: String,
    unreadableMessage: String,
    oversizedMessage: String,
): T? {
    val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (contentLength != null && contentLength > MAX_MOBILE_WORKER_REQUEST_BYTES) {
        respondMobileWorkerError(oversizedMessage, HttpStatusCode.PayloadTooLarge)
        return null
    }
    val requestText = try {
        receiveText()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        respondMobileWorkerError(unreadableMessage, HttpStatusCode.BadRequest)
        return null
    }
    if (requestText.encodeToByteArray().size > MAX_MOBILE_WORKER_REQUEST_BYTES) {
        respondMobileWorkerError(oversizedMessage, HttpStatusCode.PayloadTooLarge)
        return null
    }
    return try {
        mobileWorkerJson.decodeFromString<T>(requestText)
    } catch (error: SerializationException) {
        respondMobileWorkerError(invalidMessage, HttpStatusCode.BadRequest)
        null
    } catch (error: IllegalArgumentException) {
        respondMobileWorkerError(invalidMessage, HttpStatusCode.BadRequest)
        null
    }
}

private fun MobileWorkerEventBatchRequest.deviceInfoVersion(): String? =
    events.asSequence()
        .mapNotNull { (it.payload as? DeviceStateEvent.DeviceInfo)?.appVersion }
        .firstOrNull()

private suspend fun io.ktor.server.application.ApplicationCall.respondMobileWorkerError(
    message: String,
    status: HttpStatusCode,
) {
    respondText(
        mobileWorkerJson.encodeToString(MobileWorkerError(message)),
        ContentType.Application.Json,
        status,
    )
}

@Serializable
private data class MobileWorkerError(val error: String)

private val mobileWorkerJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

private const val MAX_MOBILE_WORKER_REQUEST_BYTES = 256 * 1024
