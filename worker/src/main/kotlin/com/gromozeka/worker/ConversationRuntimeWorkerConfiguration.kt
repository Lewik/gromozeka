package com.gromozeka.worker

import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerEnvironmentProbe
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.supportedBy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(ConversationRuntimeWorkerProperties::class)
class ConversationRuntimeWorkerConfiguration {
    @Bean
    fun workerEnvironmentProbe(
        properties: ConversationRuntimeWorkerProperties,
    ): WorkerEnvironmentProbe =
        JvmWorkerEnvironmentProbe(properties.environment.executableNames)

    @Bean
    fun conversationRuntimeWorkerDescriptor(
        properties: ConversationRuntimeWorkerProperties,
        aiToolProvider: AiToolProvider,
        workerEnvironmentProbe: WorkerEnvironmentProbe,
    ): ConversationRuntimeWorkerDescriptor {
        val workerId = properties.id
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let(::ConversationRuntimeWorkerId)
            ?: error("gromozeka.runtime.worker.id is required")
        require(properties.capabilities.isNotEmpty()) {
            "gromozeka.runtime.worker.capabilities must declare at least one capability"
        }
        val tools = if (ConversationRuntimeWorkerCapability.TOOL_EXECUTION in properties.capabilities) {
            aiToolProvider.getTools()
        } else {
            emptyList()
        }
            .supportedBy(properties.capabilities)
            .map { AiToolDescriptor(it.definition, it.metadata) }
            .sortedBy { it.definition.name }
        return ConversationRuntimeWorkerDescriptor(
            id = workerId,
            capabilities = properties.capabilities,
            tools = tools,
            environmentProfile = workerEnvironmentProbe.collectProfile(),
        )
    }
}

@ConfigurationProperties("gromozeka.runtime.worker")
data class ConversationRuntimeWorkerProperties(
    val id: String = "",
    val capabilities: Set<ConversationRuntimeWorkerCapability> = emptySet(),
    val environment: WorkerEnvironmentProperties = WorkerEnvironmentProperties(),
)

data class WorkerEnvironmentProperties(
    val executableNames: Set<String> = DEFAULT_WORKER_EXECUTABLE_NAMES,
)

private val DEFAULT_WORKER_EXECUTABLE_NAMES = setOf(
    "adb",
    "awk",
    "aws",
    "bash",
    "cargo",
    "cmd",
    "cmake",
    "curl",
    "docker",
    "dotnet",
    "ffmpeg",
    "gh",
    "git",
    "go",
    "gradle",
    "grep",
    "java",
    "jq",
    "kubectl",
    "make",
    "mvn",
    "ninja",
    "node",
    "npm",
    "podman",
    "powershell",
    "pwsh",
    "python",
    "python3",
    "rg",
    "rustc",
    "sed",
    "sh",
    "swift",
    "terraform",
    "wget",
    "xcodebuild",
    "zsh",
)
