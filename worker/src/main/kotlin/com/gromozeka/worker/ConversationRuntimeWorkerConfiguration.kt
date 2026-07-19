package com.gromozeka.worker

import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn

@Configuration
@EnableConfigurationProperties(ConversationRuntimeWorkerProperties::class)
class ConversationRuntimeWorkerConfiguration {

    @Bean
    @DependsOn("database")
    fun conversationRuntimeWorkerDescriptor(
        properties: ConversationRuntimeWorkerProperties,
        projectService: ProjectDomainService,
    ): ConversationRuntimeWorkerDescriptor {
        val workerId = properties.id
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: error("gromozeka.runtime.worker.id is required")
        require(properties.capabilities.isNotEmpty()) {
            "gromozeka.runtime.worker.capabilities must declare at least one capability"
        }
        val projectAffinities = runBlocking {
            properties.projectPaths.mapTo(mutableSetOf()) { path ->
                val project = projectService.getOrCreate(path.trim())
                ConversationRuntimeWorkerAffinity(
                    kind = ConversationRuntimeWorkerAffinity.Kind.PROJECT,
                    value = project.id.value,
                )
            }
        }
        return ConversationRuntimeWorkerDescriptor(
            id = ConversationRuntimeWorkerId(workerId),
            capabilities = properties.capabilities,
            affinities = projectAffinities + properties.affinities.map { affinity ->
                    ConversationRuntimeWorkerAffinity(
                        kind = affinity.kind,
                        value = affinity.value.trim().also { value ->
                            require(value.isNotEmpty()) {
                                "gromozeka.runtime.worker.affinities values must not be blank"
                            }
                        },
                    )
                },
        )
    }
}

@ConfigurationProperties("gromozeka.runtime.worker")
data class ConversationRuntimeWorkerProperties(
    val id: String = "",
    val capabilities: Set<ConversationRuntimeWorkerCapability> = emptySet(),
    val projectPaths: List<String> = emptyList(),
    val affinities: List<Affinity> = emptyList(),
) {
    data class Affinity(
        val kind: ConversationRuntimeWorkerAffinity.Kind,
        val value: String,
    )
}
