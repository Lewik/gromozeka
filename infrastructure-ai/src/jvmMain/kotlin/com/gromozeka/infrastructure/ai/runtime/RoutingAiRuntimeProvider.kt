package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.SettingsProvider
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service

@Service
internal class RoutingAiRuntimeProvider(
    private val backends: List<AiRuntimeBackend>,
    private val settingsProvider: SettingsProvider,
) : AiRuntimeProvider {

    override fun getRuntime(
        selection: AiRuntimeSelection,
        workspaceRootPath: String?
    ): AiRuntime {
        val resolved = settingsProvider.resolveAiRuntime(selection)
        val backend = backends.firstOrNull { it.supports(resolved.connection.kind) }
            ?: error("No AI runtime backend registered for connection kind ${resolved.connection.kind}")

        return ModelDefaultAiRuntime(
            delegate = backend.createRuntime(resolved.connection, resolved.modelConfiguration, workspaceRootPath),
            defaults = resolved.modelConfiguration.defaultParameters,
        )
    }
}

class ModelDefaultAiRuntime(
    private val delegate: AiRuntime,
    private val defaults: AiModelConfiguration.DefaultParameters,
) : AiRuntime {
    override val capabilities: AiRuntimeCapabilities
        get() = delegate.capabilities

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse =
        delegate.call(request.withModelDefaults(defaults))

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> =
        delegate.stream(request.withModelDefaults(defaults))
}

fun AiRuntimeRequest.withModelDefaults(
    defaults: AiModelConfiguration.DefaultParameters,
): AiRuntimeRequest =
    copy(
        options = options.copy(
            maxOutputTokens = options.maxOutputTokens ?: defaults.maxOutputTokens,
            reasoning = options.reasoning ?: defaults.reasoning,
        )
    )
