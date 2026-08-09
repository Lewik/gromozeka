package com.gromozeka.worker

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeExecutorDescriptor
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerEnvironmentProbe
import com.gromozeka.shared.uuid.uuid7
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(ConversationRuntimeWorkerProperties::class)
class ConversationRuntimeWorkerConfiguration {
    @Bean
    fun conversationRuntimeWorkerIdentity(
        properties: ConversationRuntimeWorkerProperties,
    ): ConversationRuntimeWorkerIdentity =
        ConversationRuntimeWorkerIdentity(
            workerId = properties.requiredWorkerId(),
            sessionId = ConversationRuntimeWorkerSessionId(uuid7()),
        )

    @Bean
    fun workerEnvironmentProbe(
        properties: ConversationRuntimeWorkerProperties,
    ): WorkerEnvironmentProbe =
        JvmWorkerEnvironmentProbe(properties.environment.executableNames)

    @Bean
    fun computerUsePlatformAccess(): ComputerUsePlatformAccess =
        JvmComputerUsePlatformAccess()

    @Bean
    fun conversationRuntimeWorkerDescriptor(
        properties: ConversationRuntimeWorkerProperties,
        workerEnvironmentProbe: WorkerEnvironmentProbe,
    ): ConversationRuntimeWorkerDescriptor {
        val workerId = properties.requiredWorkerId()
        require(properties.capabilities.isNotEmpty()) {
            "gromozeka.runtime.worker.capabilities must declare at least one capability"
        }
        return ConversationRuntimeWorkerDescriptor(
            id = workerId,
            capabilities = properties.capabilities,
            tools = emptyList(),
            environmentProfile = workerEnvironmentProbe.collectProfile(),
        )
    }

    @Bean
    fun conversationRuntimeExecutorDescriptor(
        workerIdentity: ConversationRuntimeWorkerIdentity,
        workerDescriptor: ConversationRuntimeWorkerDescriptor,
    ): ConversationRuntimeExecutorDescriptor =
        ConversationRuntimeExecutorDescriptor(
            identity = ConversationRuntimeExecutorIdentity.Worker(workerIdentity),
            capabilities = workerDescriptor.capabilities,
        )
}

@ConfigurationProperties("gromozeka.runtime.worker")
data class ConversationRuntimeWorkerProperties(
    val id: String = "",
    val capabilities: Set<ConversationRuntimeCapability> = emptySet(),
    val environment: WorkerEnvironmentProperties = WorkerEnvironmentProperties(),
)

data class WorkerEnvironmentProperties(
    val executableNames: Set<String> = DEFAULT_WORKER_EXECUTABLE_NAMES,
)

private fun ConversationRuntimeWorkerProperties.requiredWorkerId(): ConversationRuntimeWorkerId =
    id.trim()
        .takeIf { it.isNotEmpty() }
        ?.let(::ConversationRuntimeWorkerId)
        ?: error("gromozeka.runtime.worker.id is required")

private val DEFAULT_WORKER_EXECUTABLE_NAMES = setOf(
    "adb",
    "awk",
    "aws",
    "bash",
    "cargo",
    "cmd",
    "cmake",
    "claude",
    "copilot",
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
    "npx",
    "pactl",
    "paplay",
    "podman",
    "powershell",
    "pw-play",
    "pwsh",
    "python",
    "python3",
    "rec",
    "rg",
    "rustc",
    "sed",
    "sh",
    "sox",
    "swift",
    "terraform",
    "wget",
    "xcodebuild",
    "zsh",
)
