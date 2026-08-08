package com.gromozeka.server

import com.gromozeka.application.service.ContextStateApplicationService
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.remote.protocol.MobileWorkerEventBatchRequest
import com.gromozeka.remote.protocol.MobileWorkerEventBatchResponse
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
) {
    post("/api/mobile-worker/events") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (!call.isSecureTransport()) {
            call.respondMobileWorkerError("Mobile Worker synchronization requires HTTPS", HttpStatusCode.UpgradeRequired)
            return@post
        }
        val authenticated = authenticationService.authenticate(call.request.header(HttpHeaders.Authorization))
        if (authenticated == null) {
            call.respondMobileWorkerError("Mobile Worker authentication required", HttpStatusCode.Unauthorized)
            return@post
        }
        if (authenticated.worker.kind != WorkerResource.Kind.MOBILE_DEVICE) {
            call.respondMobileWorkerError("Credential does not belong to a mobile device Worker", HttpStatusCode.Forbidden)
            return@post
        }
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_MOBILE_WORKER_REQUEST_BYTES) {
            call.respondMobileWorkerError("Mobile Worker event batch is too large", HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val requestText = try {
            call.receiveText()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            call.respondMobileWorkerError("Mobile Worker event batch could not be read", HttpStatusCode.BadRequest)
            return@post
        }
        if (requestText.encodeToByteArray().size > MAX_MOBILE_WORKER_REQUEST_BYTES) {
            call.respondMobileWorkerError("Mobile Worker event batch is too large", HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val request = try {
            mobileWorkerJson.decodeFromString<MobileWorkerEventBatchRequest>(requestText)
        } catch (error: SerializationException) {
            call.respondMobileWorkerError("Invalid Mobile Worker event batch", HttpStatusCode.BadRequest)
            return@post
        } catch (error: IllegalArgumentException) {
            call.respondMobileWorkerError("Invalid Mobile Worker event batch", HttpStatusCode.BadRequest)
            return@post
        }
        try {
            val result = contextStateService.ingestMobileWorker(
                worker = authenticated.worker,
                observations = request.events.map { it.toObservation() },
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
}

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
