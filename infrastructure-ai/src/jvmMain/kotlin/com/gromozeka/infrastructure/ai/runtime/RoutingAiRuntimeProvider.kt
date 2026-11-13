package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.AiContextInspection
import com.gromozeka.domain.model.ai.AiRequestTokenEstimator
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AI_REPLAY_THREAD_METADATA_KEY
import com.gromozeka.domain.model.ai.nativeCompactionProvider
import com.gromozeka.domain.model.ai.projectedMessages
import com.gromozeka.domain.model.ai.originalCollaborationMessages
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
                delegate = backend.createRuntime(runtime, workspaceRootPath),
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

    override val tokenEstimator: AiRequestTokenEstimator get() = delegate.tokenEstimator

    override suspend fun inspectContext(request: AiRuntimeRequest, allowProviderCount: Boolean): AiContextInspection {
        val prepared = validatedRequest(request)
        val inspection = delegate.inspectContext(prepared, allowProviderCount)
        return if (runtime.connection.kind in setOf(AiConnection.Kind.CLAUDE_CODE, AiConnection.Kind.OPENAI_SUBSCRIPTION)) inspection
            else AiContextMeasurements.calibrate(runtime, prepared, inspection, tokenEstimator.id)
    }

    override suspend fun compact(request: AiRuntimeRequest): AiRuntimeResponse {
        require(request.options.usagePurpose == "CONTEXT_COMPACTION") { "Native compaction requires its own usage purpose" }
        return delegate.compact(validatedRequest(request)).withReplayScope(request)
    }

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        val prepared = validatedRequest(request)
        return AiContextMeasurements.record(runtime, prepared, delegate.call(prepared), tokenEstimator).withReplayScope(request)
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> {
        val prepared = validatedRequest(request)
        return delegate.stream(prepared).map { AiContextMeasurements.record(runtime, prepared, it, tokenEstimator).withReplayScope(request) }
    }

    private fun validatedRequest(request: AiRuntimeRequest): AiRuntimeRequest {
        val prepared = prepare(request)
        AiRuntimeInputValidator.requireSupported(runtime, prepared.messages)
        return if (runtime.connection.kind == AiConnection.Kind.CLAUDE_CODE)
            request.copy(messages = request.originalCollaborationMessages()) else prepared
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

    override val tokenEstimator: AiRequestTokenEstimator = object : AiRequestTokenEstimator {
        override val id: String get() = delegate.tokenEstimator.id
        override fun input(request: AiRuntimeRequest): Int = delegate.tokenEstimator.input(request.withModelDefaults(defaults))
        override fun output(messages: List<AiAssistantMessage>): Int = delegate.tokenEstimator.output(messages)
    }

    override suspend fun inspectContext(request: AiRuntimeRequest, allowProviderCount: Boolean): AiContextInspection =
        delegate.inspectContext(request.withModelDefaults(defaults), allowProviderCount)

    override suspend fun compact(request: AiRuntimeRequest): AiRuntimeResponse =
        delegate.compact(request.withModelDefaults(defaults))

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
            maxOutputTokens = options.maxOutputTokens ?: defaults.maxOutputTokens.takeUnless { options.usagePurpose == "MESSAGE_SQUASH" },
            reasoning = options.reasoning ?: defaults.reasoning,
        )
    )
