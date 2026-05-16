package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.SettingsProvider
import org.springframework.stereotype.Service

@Service
internal class RoutingAiRuntimeProvider(
    private val backends: List<AiRuntimeBackend>,
    private val settingsProvider: SettingsProvider,
) : AiRuntimeProvider {

    override fun getRuntime(
        selection: AiRuntimeSelection,
        projectPath: String?
    ): AiRuntime {
        val resolved = settingsProvider.resolveAiRuntime(selection)
        val backend = backends.firstOrNull { it.supports(resolved.connection.kind) }
            ?: error("No AI runtime backend registered for connection kind ${resolved.connection.kind}")

        return backend.createRuntime(resolved.connection, resolved.modelConfiguration, projectPath)
    }
}
