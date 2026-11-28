package com.gromozeka.infrastructure.ai

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.LiveTranscriptionProvider
import com.gromozeka.domain.service.LiveTranscriptionSession
import com.gromozeka.domain.service.ResolvedAiRuntime
import org.springframework.stereotype.Service

interface LiveTranscriptionBackend {
    fun supports(connectionKind: AiConnection.Kind): Boolean

    suspend fun open(
        userStableId: String,
        runtime: ResolvedAiRuntime,
        languages: List<String>,
        manualTurnControl: Boolean,
        nativeTranslationLanguage: String?,
    ): LiveTranscriptionSession
}

@Service
class RoutingLiveTranscriptionProvider(
    private val configurations: AiConfigurationProvider,
    private val backends: List<LiveTranscriptionBackend>,
) : LiveTranscriptionProvider {
    override suspend fun open(
        userStableId: String,
        selection: AiRuntimeSelection,
        languages: List<String>,
        manualTurnControl: Boolean,
    ): LiveTranscriptionSession = open(userStableId, selection, languages, manualTurnControl, null)

    override suspend fun open(
        userStableId: String,
        selection: AiRuntimeSelection,
        languages: List<String>,
        manualTurnControl: Boolean,
        nativeTranslationLanguage: String?,
    ): LiveTranscriptionSession {
        val runtime = configurations.resolveAiRuntime(selection)
        require(runtime.connection.executionTarget == AiExecutionTarget.Server) {
            "Live transcription requires a Server-targeted connection"
        }
        require(AiModelCapability.REALTIME_TRANSCRIPTION in runtime.modelSpec.capabilities) {
            "The selected model does not support Live transcription"
        }
        val matching = backends.filter { it.supports(runtime.connection.kind) }
        require(matching.size == 1) {
            "Expected one Live transcription backend for ${runtime.connection.kind}, found ${matching.size}"
        }
        return matching.single().open(userStableId, runtime, languages, manualTurnControl, nativeTranslationLanguage)
    }
}
