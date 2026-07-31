package com.gromozeka.worker

import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.supportedBy
import org.springframework.stereotype.Service

@Service
class WorkerToolCatalog(
    private val aiToolProvider: AiToolProvider,
    properties: ConversationRuntimeWorkerProperties,
) {
    private val capabilities = properties.capabilities

    fun snapshot(): List<AiToolDescriptor> =
        if (ConversationRuntimeCapability.TOOL_EXECUTION in capabilities) {
            aiToolProvider.getTools()
                .supportedBy(capabilities)
                .filter { it.metadata.executionScope != AiToolExecutionScope.SERVER }
                .map { AiToolDescriptor(it.definition, it.metadata) }
                .sortedBy { it.definition.name }
        } else {
            emptyList()
        }
}
