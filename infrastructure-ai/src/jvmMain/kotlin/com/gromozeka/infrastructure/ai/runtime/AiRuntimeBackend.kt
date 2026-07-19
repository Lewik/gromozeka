package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.service.AiRuntime

interface AiRuntimeBackend {
    fun supports(connectionKind: AiConnection.Kind): Boolean

    fun createRuntime(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
        workspaceRootPath: String?
    ): AiRuntime
}
