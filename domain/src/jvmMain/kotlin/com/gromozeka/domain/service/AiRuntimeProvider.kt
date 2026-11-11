package com.gromozeka.domain.service

import com.gromozeka.domain.model.ai.AiContextInspection
import com.gromozeka.domain.model.ai.AiRequestTokenEstimate
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlinx.coroutines.flow.Flow

/**
 * Provider for Gromozeka's internal AI runtime.
 *
 * Application code depends on this contract instead of on Spring AI types.
 * Infrastructure may implement it using Spring AI, direct provider clients,
 * or hybrid transports.
 */
interface AiRuntimeProvider {
    fun capabilities(selection: AiRuntimeSelection): AiRuntimeCapabilities =
        getRuntime(selection, workspaceRootPath = null).capabilities

    fun getRuntime(
        selection: AiRuntimeSelection,
        workspaceRootPath: String?
    ): AiRuntime
}

interface AiRuntime {
    val capabilities: AiRuntimeCapabilities
        get() = AiRuntimeCapabilities()

    suspend fun inspectContext(request: AiRuntimeRequest): AiContextInspection = AiContextInspection(
        AiRequestTokenEstimate.input(request), AiContextInspection.Precision.ESTIMATE,
        nativeCompactionAvailable = capabilities.supportsNativeCompaction,
    )

    /** Compact only: never start the next ordinary generation. */
    suspend fun compact(request: AiRuntimeRequest): AiRuntimeResponse =
        error("This runtime does not support explicit native compaction")

    suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse

    fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse>
}

interface DirectAiRuntimeProvider {
    fun capabilities(runtime: ResolvedAiRuntime): AiRuntimeCapabilities =
        getRuntime(runtime, workspaceRootPath = null).capabilities

    fun getRuntime(
        runtime: ResolvedAiRuntime,
        workspaceRootPath: String?,
    ): AiRuntime
}
