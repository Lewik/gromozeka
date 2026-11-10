package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.ApplicationUpdateRepository
import com.gromozeka.domain.service.ApplicationUpdateCatalogSource
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.time.Clock

@Service
class ApplicationUpdateService(
    private val repository: ApplicationUpdateRepository,
    private val catalog: ApplicationUpdateCatalogSource,
) {
    suspend fun list(user: User): List<ApplicationInstallation> = repository.list().filter { canManage(user, it) }

    suspend fun poll(initial: ApplicationInstallation, report: ApplicationUpdateReport?): ApplicationInstallation =
        repository.modify(initial.id, initial) { current ->
            require(current.ownerUserId == initial.ownerUserId && current.workerId == initial.workerId &&
                current.component == initial.component && current.platform == initial.platform) { "Installation identity does not match" }
            var request = current.request
            if (request != null && !request.phase.terminal) {
                request = when {
                    initial.version == request.artifact.version -> request.copy(phase = ApplicationUpdatePhase.SUCCEEDED, message = null)
                    report?.requestId == request.id && report.phase != ApplicationUpdatePhase.SUCCEEDED -> {
                        require(report.phase in allowedTransitions(request.phase)) { "Invalid update state transition" }
                        request.copy(phase = report.phase, message = report.message)
                    }
                    else -> request
                }
            }
            val updated = current.copy(label = initial.label, version = initial.version, lastSeenAt = initial.lastSeenAt,
                unsupportedReason = initial.unsupportedReason, request = request)
            val available = available(updated)
            if (updated.automatic && available != null && updated.unsupportedReason == null &&
                (request == null || request.phase.terminal) && request?.artifact?.version != available.version
            ) updated.copy(request = newRequest(available)) else updated
        }

    fun available(installation: ApplicationInstallation): ApplicationUpdateArtifact? = catalog.current()?.artifacts
        ?.singleOrNull { it.component == installation.component && it.platform == installation.platform }
        ?.takeUnless { it.version == installation.version }

    suspend fun request(user: User, id: String): ApplicationInstallation = request(id) { canManage(user, it) }

    suspend fun requestWorker(workerId: String, id: String): ApplicationInstallation = request(id) { it.workerId == workerId }

    private suspend fun request(id: String, allowed: (ApplicationInstallation) -> Boolean): ApplicationInstallation = repository.modify(id) { current ->
        require(allowed(current)) { "Access denied" }
        require(current.unsupportedReason == null) { current.unsupportedReason ?: "Updates unsupported" }
        if (current.request?.phase?.terminal == false) current
        else current.copy(request = newRequest(requireNotNull(available(current)) { "No update is available" }))
    }

    suspend fun policy(user: User, id: String, automatic: Boolean): ApplicationInstallation = repository.modify(id) {
        require(canManage(user, it)) { "Access denied" }
        it.copy(automatic = automatic)
    }

    suspend fun workerPolicy(workerId: String, id: String, automatic: Boolean): ApplicationInstallation = repository.modify(id) {
        require(it.workerId == workerId) { "Access denied" }
        it.copy(automatic = automatic)
    }

    suspend fun cancel(user: User, id: String): ApplicationInstallation = repository.modify(id) {
        require(canManage(user, it)) { "Access denied" }
        val request = it.request
        require(request?.phase !in setOf(ApplicationUpdatePhase.INSTALLING, ApplicationUpdatePhase.WAITING_FOR_USER)) { "Installation has already been handed to the operating system" }
        if (request == null || request.phase.terminal) it
        else it.copy(request = request.copy(phase = ApplicationUpdatePhase.CANCELLED))
    }

    suspend fun get(id: String): ApplicationInstallation = requireNotNull(repository.find(id)) { "Installation not found" }

    fun canManage(user: User, installation: ApplicationInstallation): Boolean =
        user.role == User.Role.OWNER || user.id == installation.ownerUserId

    private fun newRequest(artifact: ApplicationUpdateArtifact) = ApplicationUpdateRequest(
        UUID.randomUUID().toString(), artifact, Clock.System.now().toString(),
    )

    private fun allowedTransitions(phase: ApplicationUpdatePhase): Set<ApplicationUpdatePhase> = setOf(phase, ApplicationUpdatePhase.FAILED) + when (phase) {
        ApplicationUpdatePhase.QUEUED -> setOf(ApplicationUpdatePhase.WAITING_FOR_IDLE, ApplicationUpdatePhase.DOWNLOADING)
        ApplicationUpdatePhase.WAITING_FOR_IDLE -> setOf(ApplicationUpdatePhase.DOWNLOADING)
        ApplicationUpdatePhase.DOWNLOADING -> setOf(ApplicationUpdatePhase.READY)
        ApplicationUpdatePhase.READY -> setOf(ApplicationUpdatePhase.WAITING_FOR_IDLE, ApplicationUpdatePhase.WAITING_FOR_USER, ApplicationUpdatePhase.INSTALLING)
        ApplicationUpdatePhase.WAITING_FOR_USER -> setOf(ApplicationUpdatePhase.INSTALLING)
        ApplicationUpdatePhase.INSTALLING -> setOf(ApplicationUpdatePhase.WAITING_FOR_USER)
        else -> emptySet()
    }
}
