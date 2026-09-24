package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AI_REPLAY_THREAD_METADATA_KEY
import com.gromozeka.domain.model.ai.nativeCompactionProvider
import com.gromozeka.domain.model.ai.projectedMessages
import kotlinx.coroutines.flow.map
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.DirectAiRuntimeProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service

@Service
internal class RoutingAiRuntimeProvider(
    private val backends: List<AiRuntimeBackend>,
) : DirectAiRuntimeProvider {

    override fun capabilities(runtime: ResolvedAiRuntime): AiRuntimeCapabilities {
        val backend = backendFor(runtime.connection.kind)
        return backend.capabilities(runtime.connection, runtime.modelConfiguration)
    }

    override fun getRuntime(
        runtime: ResolvedAiRuntime,
        workspaceRootPath: String?
    ): AiRuntime {
        val backend = backendFor(runtime.connection.kind)

        return AiInputValidatingRuntime(
            delegate = ModelDefaultAiRuntime(
                delegate = backend.createRuntime(runtime.connection, runtime.modelConfiguration, workspaceRootPath),
                defaults = runtime.modelConfiguration.defaultParameters,
            ),
            runtime = runtime,
        )
    }

    private fun backendFor(connectionKind: com.gromozeka.domain.model.ai.AiConnection.Kind): AiRuntimeBackend =
        backends.firstOrNull { it.supports(connectionKind) }
            ?: error("No AI runtime backend registered for connection kind $connectionKind")
}

class AiInputValidatingRuntime(
    private val delegate: AiRuntime,
    private val runtime: ResolvedAiRuntime,
) : AiRuntime {
    override val capabilities: AiRuntimeCapabilities
        get() = delegate.capabilities

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        val prepared = prepare(request)
        AiRuntimeInputValidator.requireSupported(runtime, prepared.messages)
        return delegate.call(if (runtime.connection.kind == AiConnection.Kind.CLAUDE_CODE) request else prepared)
            .withReplayScope(request)
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> {
        val prepared = prepare(request)
        AiRuntimeInputValidator.requireSupported(runtime, prepared.messages)
        return delegate.stream(if (runtime.connection.kind == AiConnection.Kind.CLAUDE_CODE) request else prepared)
            .map { it.withReplayScope(request) }
    }

    private fun prepare(request: AiRuntimeRequest): AiRuntimeRequest = request.copy(
        messages = request.projectedMessages(runtime.connection.kind.nativeCompactionProvider()),
    )

    private fun AiRuntimeResponse.withReplayScope(request: AiRuntimeRequest): AiRuntimeResponse {
        // Claude Code keeps canonical history fingerprints in its own session store.
        if (runtime.connection.kind == AiConnection.Kind.CLAUDE_CODE) return this
        val thread = request.options.toolContext["threadId"] as? String ?: return this
        return copy(messages = messages.map { it.copy(metadata = it.metadata + (AI_REPLAY_THREAD_METADATA_KEY to thread)) })
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
