package com.gromozeka.worker

import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.supportedBy
import org.springframework.stereotype.Service

@Service
class WorkerToolCatalog(
    private val aiToolProvider: AiToolProvider,
    descriptor: ConversationRuntimeWorkerDescriptor,
) {
    private val capabilities = descriptor.capabilities

    fun snapshot(): List<AiToolDescriptor> =
        aiToolProvider.getTools()
            .supportedBy(capabilities)
            .filter { it.metadata.executionScope != AiToolExecutionScope.CONVERSATION_RUNTIME }
            .map { AiToolDescriptor(it.definition, it.metadata) }
            .sortedBy { it.definition.name }
}
