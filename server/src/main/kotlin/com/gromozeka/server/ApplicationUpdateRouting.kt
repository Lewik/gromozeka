package com.gromozeka.server

import com.gromozeka.application.service.ApplicationUpdateService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.remote.protocol.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest
import kotlin.time.Clock

@RestController
@RequestMapping("/api/updates/v1")
internal class ApplicationUpdateRouting(
    private val updates: ApplicationUpdateService,
    private val catalog: ApplicationUpdateCatalogFiles,
    private val authentication: AuthenticationService,
    private val workers: WorkerGatewayAuthenticationService,
    private val settings: ServerHttpSettings,
) {
    @PostMapping("/{kind:client|worker}/poll")
    suspend fun poll(@PathVariable kind: String, request: HttpServletRequest, response: HttpServletResponse) {
        secure(request, response)
        val body = httpJson.decodeFromString<ApplicationUpdatePoll>(request.readBoundedBody(8192).decodeToString(throwOnInvalidSequence = true))
        val worker = if (kind == "worker") worker(request) else null
        val owner = worker?.ownerUserId ?: request.requireAuthenticated(authentication).principal.user.id
        require(body.component == if (worker == null) UpdateComponent.CLIENT else UpdateComponent.WORKER)
        val id = worker?.let { workerInstallationId(it.id.value) } ?: body.installationId.also { require(it.startsWith("client-")) }
        val initial = ApplicationInstallation(id, owner, worker?.id?.value, body.component, body.platform,
            body.label, body.version, Clock.System.now().toString(), unsupportedReason = body.unsupportedReason)
        val installation = updates.poll(initial, body.report)
        response.writeJson(ApplicationUpdatePollResponse(installation, updates.available(installation)))
    }

    @GetMapping("/installations")
    suspend fun list(request: HttpServletRequest, response: HttpServletResponse) {
        secure(request, response)
        response.writeJson(ApplicationUpdateInventory(updates.list(request.requireAuthenticated(authentication).principal.user), catalog.current()))
    }

    @PostMapping("/worker/request")
    suspend fun workerUpdate(request: HttpServletRequest, response: HttpServletResponse) {
        secure(request, response)
        val worker = worker(request)
        response.writeJson(updates.requestWorker(worker.id.value, workerInstallationId(worker.id.value)))
    }

    @PostMapping("/worker/policy")
    suspend fun workerPolicy(request: HttpServletRequest, response: HttpServletResponse) {
        secure(request, response)
        val worker = worker(request)
        val body = httpJson.decodeFromString<ApplicationUpdatePolicy>(request.readBoundedBody(1024).decodeToString(throwOnInvalidSequence = true))
        response.writeJson(updates.workerPolicy(worker.id.value, workerInstallationId(worker.id.value), body.automatic))
    }

    @PostMapping("/installations/{id}/request")
    suspend fun update(@PathVariable id: String, request: HttpServletRequest, response: HttpServletResponse) {
        secure(request, response)
        response.writeJson(updates.request(request.requireAuthenticated(authentication).principal.user, id))
    }

    @PostMapping("/installations/{id}/cancel")
    suspend fun cancel(@PathVariable id: String, request: HttpServletRequest, response: HttpServletResponse) {
        secure(request, response)
        response.writeJson(updates.cancel(request.requireAuthenticated(authentication).principal.user, id))
    }

    @PostMapping("/installations/{id}/policy")
    suspend fun policy(@PathVariable id: String, request: HttpServletRequest, response: HttpServletResponse) {
        secure(request, response)
        val body = httpJson.decodeFromString<ApplicationUpdatePolicy>(request.readBoundedBody(1024).decodeToString(throwOnInvalidSequence = true))
        response.writeJson(updates.policy(request.requireAuthenticated(authentication).principal.user, id, body.automatic))
    }

    @GetMapping("/{kind:client|worker}/artifacts/{id}/{requestId}")
    suspend fun artifact(@PathVariable kind: String, @PathVariable id: String, @PathVariable requestId: String,
        request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<FileSystemResource> {
        secure(request, response)
        val installation = updates.get(id)
        if (kind == "worker") require(installation.workerId == worker(request).id.value && installation.component == UpdateComponent.WORKER)
        else require(installation.component == UpdateComponent.CLIENT && updates.canManage(request.requireAuthenticated(authentication).principal.user, installation))
        val update = requireNotNull(installation.request).also { require(it.id == requestId && !it.phase.terminal) }
        val file = catalog.artifact(update.artifact)
        return ResponseEntity.ok().contentLength(file.length())
            .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${file.name}\"")
            .body(FileSystemResource(file))
    }

    private suspend fun worker(request: HttpServletRequest): WorkerResource =
        (workers.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION))
            ?: throw HttpAuthenticationException(HttpStatus.UNAUTHORIZED, "Worker authentication required")).worker

    private fun secure(request: HttpServletRequest, response: HttpServletResponse) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        if (!request.isSecureTransport(settings)) throw HttpAuthenticationException(HttpStatus.UPGRADE_REQUIRED, "Updates require HTTPS")
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException, response: HttpServletResponse) =
        response.writeHttpError(HttpStatus.BAD_REQUEST, error.message?.take(256) ?: "Invalid update request")

    @ExceptionHandler(HttpRequestBodyTooLarge::class)
    fun oversized(response: HttpServletResponse) = response.writeHttpError(HttpStatus.PAYLOAD_TOO_LARGE, "Update request is too large")
}

internal fun workerInstallationId(id: String) = "worker-" + MessageDigest.getInstance("SHA-256")
    .digest(id.encodeToByteArray()).take(16).joinToString("") { "%02x".format(it) }
