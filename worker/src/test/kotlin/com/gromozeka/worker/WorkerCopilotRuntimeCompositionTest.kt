package com.gromozeka.worker

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.service.DirectAiRuntimeProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerCopilotRuntimeCompositionTest {
    @Test
    fun `Worker composes Copilot runtime without Server credential storage`() {
        val home = Files.createTempDirectory("gromozeka-worker-copilot-")
        val previousMode = System.getProperty("GROMOZEKA_MODE")
        val previousHome = System.getProperty("GROMOZEKA_HOME")
        System.setProperty("GROMOZEKA_MODE", "test")
        System.setProperty("GROMOZEKA_HOME", home.toString())

        var context: ConfigurableApplicationContext? = null
        try {
            context = SpringApplicationBuilder(GromozekaWorkerApplication::class.java)
                .web(WebApplicationType.NONE)
                .profiles("e2e")
                .run(
                    "--gromozeka.worker-gateway.enabled=false",
                    "--gromozeka.runtime.worker.id=copilot-composition-test",
                    "--gromozeka.runtime.worker.capabilities[0]=AI_REQUEST_RESPONSE",
                    "--logging.file.path=${home.resolve("logs")}",
                )

            val provider = context.getBean(DirectAiRuntimeProvider::class.java)
            assertEquals(
                false,
                provider.capabilities(copilotRuntime()).supportsAutoCompaction,
            )
        } finally {
            context?.close()
            restoreProperty("GROMOZEKA_MODE", previousMode)
            restoreProperty("GROMOZEKA_HOME", previousHome)
            deleteRecursively(home)
        }
    }

    private fun copilotRuntime(): ResolvedAiRuntime {
        val connection = AiConnection.GitHubCopilot(
            id = AiConnection.Id("copilot-worker"),
            displayName = "Copilot Worker",
            executionTarget = AiExecutionTarget.Worker("copilot-composition-test"),
        )
        return ResolvedAiRuntime(
            connection = connection,
            modelConfiguration = AiModelConfiguration(
                id = AiModelConfiguration.Id("copilot-worker-terra"),
                connectionId = connection.id,
                providerModelId = "gpt-5.6-terra",
                displayName = "Copilot Worker Terra",
            ),
            modelSpec = AiModelSpec(
                id = "gpt-5.6-terra",
                provider = AiProvider.OPENAI,
                capabilities = setOf(
                    AiModelCapability.TEXT_GENERATION,
                    AiModelCapability.TOOL_CALLING,
                ),
                limits = AiModelSpec.Limits(
                    textGeneration = AiModelSpec.Limits.TextGeneration(128_000),
                ),
            ),
        )
    }

    private fun restoreProperty(name: String, value: String?) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { files ->
            files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
