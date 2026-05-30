package com.gromozeka.server

import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.DefaultConversationRuntimeWorkerCapabilities
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration
class ConversationRuntimeWorkerConfiguration {

    @Bean
    fun conversationRuntimeWorkerDescriptor(environment: Environment): ConversationRuntimeWorkerDescriptor =
        ConversationRuntimeWorkerDescriptor(
            id = environment.getProperty("gromozeka.runtime.worker.id")?.takeIf { it.isNotBlank() },
            capabilities = parseCapabilities(environment.getProperty("gromozeka.runtime.worker.capabilities")),
            affinities = parseAffinities(environment.getProperty("gromozeka.runtime.worker.affinities")),
        )

    private fun parseCapabilities(value: String?): Set<ConversationRuntimeWorkerCapability> {
        if (value.isNullOrBlank()) {
            return DefaultConversationRuntimeWorkerCapabilities
        }
        return value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapTo(mutableSetOf()) { capability ->
                ConversationRuntimeWorkerCapability.valueOf(capability)
            }
            .ifEmpty { DefaultConversationRuntimeWorkerCapabilities }
    }

    private fun parseAffinities(value: String?): Set<ConversationRuntimeWorkerAffinity> {
        if (value.isNullOrBlank()) {
            return emptySet()
        }
        return value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapTo(mutableSetOf()) { raw ->
                val parts = raw.split('=', limit = 2)
                require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    "Runtime worker affinity must use KIND=value syntax: $raw"
                }
                ConversationRuntimeWorkerAffinity(
                    kind = ConversationRuntimeWorkerAffinity.Kind.valueOf(parts[0].trim()),
                    value = parts[1].trim(),
                )
            }
    }
}
