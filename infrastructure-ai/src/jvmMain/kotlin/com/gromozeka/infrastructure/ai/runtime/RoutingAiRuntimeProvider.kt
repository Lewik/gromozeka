package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import org.springframework.stereotype.Service

@Service
internal class RoutingAiRuntimeProvider(
    private val backends: List<AiRuntimeBackend>,
) : AiRuntimeProvider {

    override fun getRuntime(
        provider: AIProvider,
        modelName: String,
        projectPath: String?
    ): AiRuntime {
        val backend = backends.firstOrNull { it.supports(provider) }
            ?: error("No AI runtime backend registered for provider $provider")

        return backend.createRuntime(provider, modelName, projectPath)
    }
}
