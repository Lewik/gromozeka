package com.gromozeka.application.service

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import kotlin.time.Clock
import org.springframework.stereotype.Service

@Service
class DefaultConversationRuntimeWorkerTargetResolver(
    private val workerRegistry: ConversationRuntimeWorkerRegistry,
) : ConversationRuntimeWorkerTargetResolver {
    override suspend fun requireRegistered(
        workerId: ConversationRuntimeWorkerId,
        capability: ConversationRuntimeCapability,
    ): ConversationRuntimeWorkerIdentity {
        val registration = workerRegistry.find(workerId) ?: error("Worker not found: ${workerId.value}")
        require(capability in registration.capabilities) { "Worker ${workerId.value} does not support ${capability.name}" }
        return registration.identity
    }

    override suspend fun requireOnline(
        workerId: ConversationRuntimeWorkerId,
        capability: ConversationRuntimeCapability,
    ): ConversationRuntimeWorkerIdentity {
        val registration = workerRegistry.find(workerId)
            ?: error("Worker not found: ${workerId.value}")
        val staleBefore = Clock.System.now() - ConversationRuntimeTiming.workerRegistrationStaleAfter
        require(registration.isOnline(staleBefore)) {
            "Worker is offline: ${workerId.value}"
        }
        require(capability in registration.capabilities) {
            "Worker ${workerId.value} does not support ${capability.name}"
        }
        return registration.identity
    }
}
