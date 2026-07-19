package com.gromozeka.domain.service

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
    fun getRuntime(
        selection: AiRuntimeSelection,
        workspaceRootPath: String?
    ): AiRuntime
}

interface AiRuntime {
    val capabilities: AiRuntimeCapabilities
        get() = AiRuntimeCapabilities()

    suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse

    fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse>
}
