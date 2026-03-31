package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.service.AiRuntime

interface AiRuntimeBackend {
    fun supports(provider: AIProvider): Boolean

    fun createRuntime(
        provider: AIProvider,
        modelName: String,
        projectPath: String?
    ): AiRuntime
}
