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
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

private val workerEventLog = KLoggers.logger("WorkerEventRouting")
private val workerEventJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
private const val MAX_WORKER_EVENT_REQUEST_BYTES = 256 * 1024

@RestController
internal class WorkerEventRouting(
    private val authentication: WorkerGatewayAuthenticationService,
    private val contextState: ContextStateApplicationService,
    private val contacts: WorkerContactApplicationService,
    private val settings: ServerHttpSettings,
) {
    @PostMapping("/api/worker/events")
    suspend fun events(request: HttpServletRequest, response: HttpServletResponse) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        val authenticated = authenticate(request, response) ?: return
        val body = request.receiveWorkerEventRequest<WorkerEventBatchRequest>(response,
            "Invalid Worker event batch", "Worker event batch could not be read", "Worker event batch is too large") ?: return
        try {
            val result = contextState.ingestWorker(worker = authenticated.worker, observations = body.events.map { it.toObservation() })
            val contact = body.contact
            contacts.record(worker = authenticated.worker, requestId = contact?.requestId ?: body.events.first().id,
                kind = WorkerContactKind.EVENT_BATCH, appState = contact?.appState ?: WorkerAppState.UNKNOWN,
                appVersion = contact?.appVersion ?: body.deviceInfoVersion(), workerSentAt = contact?.sentAt,
                eventCount = body.events.size, pendingEventCount = contact?.pendingEventCount, receivedAt = result.receivedAt)
            response.writeJson(WorkerEventBatchResponse(acceptedEventIds = result.acceptedIds,
                duplicateEventIds = result.duplicateIds, serverReceivedAt = result.receivedAt), json = workerEventJson)
        } catch (error: IllegalArgumentException) {
            response.writeHttpError(HttpStatus.BAD_REQUEST, error.message ?: "Worker synchronization failed")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            workerEventLog.error { "Worker synchronization failed: worker=${authenticated.worker.id.value} error=${error::class.simpleName}" }
            response.writeHttpError(HttpStatus.INTERNAL_SERVER_ERROR, "Worker synchronization failed")
        }
    }

    @PostMapping("/api/worker/heartbeat")
    suspend fun heartbeat(request: HttpServletRequest, response: HttpServletResponse) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        val authenticated = authenticate(request, response) ?: return
        val body = request.receiveWorkerEventRequest<WorkerHeartbeatRequest>(response,
            "Invalid Worker heartbeat", "Worker heartbeat could not be read", "Worker heartbeat is too large") ?: return
        try {
            val receivedAt = contacts.record(worker = authenticated.worker, requestId = body.contact.requestId,
                kind = WorkerContactKind.HEARTBEAT, appState = body.contact.appState, appVersion = body.contact.appVersion,
                workerSentAt = body.contact.sentAt, eventCount = 0, pendingEventCount = body.contact.pendingEventCount)
            response.writeJson(WorkerHeartbeatResponse(receivedAt), json = workerEventJson)
        } catch (error: IllegalArgumentException) {
            response.writeHttpError(HttpStatus.BAD_REQUEST, error.message ?: "Worker heartbeat failed")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            workerEventLog.error { "Worker heartbeat failed: worker=${authenticated.worker.id.value} error=${error::class.simpleName}" }
            response.writeHttpError(HttpStatus.INTERNAL_SERVER_ERROR, "Worker heartbeat failed")
        }
    }

    private suspend fun authenticate(request: HttpServletRequest, response: HttpServletResponse): AuthenticatedWorkerGateway? {
        if (!request.isSecureTransport(settings)) {
            response.writeHttpError(HttpStatus.UPGRADE_REQUIRED, "Worker synchronization requires HTTPS")
            return null
        }
        val worker = authentication.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION))
        if (worker == null) {
            response.writeHttpError(HttpStatus.UNAUTHORIZED, "Worker authentication required")
            return null
        }
        if (worker.worker.subjectUserId == null) {
            response.writeHttpError(HttpStatus.FORBIDDEN, "Worker is not bound to a user for context reporting")
            return null
        }
        return worker
    }
}

private suspend inline fun <reified T> HttpServletRequest.receiveWorkerEventRequest(
    response: HttpServletResponse, invalid: String, unreadable: String, oversized: String,
): T? {
    val bytes = try { readBoundedBody(MAX_WORKER_EVENT_REQUEST_BYTES) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: HttpRequestBodyTooLarge) { response.writeHttpError(HttpStatus.PAYLOAD_TOO_LARGE, oversized); return null }
    catch (_: Exception) { response.writeHttpError(HttpStatus.BAD_REQUEST, unreadable); return null }
    return try { workerEventJson.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true)) }
    catch (_: IllegalArgumentException) { response.writeHttpError(HttpStatus.BAD_REQUEST, invalid); null }
}

private fun WorkerEventBatchRequest.deviceInfoVersion(): String? = events.asSequence()
    .mapNotNull { (it.payload as? DeviceStateEvent.DeviceInfo)?.appVersion }.firstOrNull()
