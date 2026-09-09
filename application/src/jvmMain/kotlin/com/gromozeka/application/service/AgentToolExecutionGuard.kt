package com.gromozeka.application.service

import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskGuard
import org.springframework.stereotype.Service

@Service
class AgentToolExecutionGuard(
    private val agents: AgentDomainService,
    private val conversations: ConversationDomainService,
    private val tools: DistributedAiToolCatalog,
) : ConversationRuntimeTaskGuard {
    override suspend fun validate(task: ConversationRuntimeTask) {
        val payload = task.payload as? ConversationRuntimeTask.Payload.ToolExecution ?: return
        val agent = agents.findById(payload.agentDefinitionId) ?: error("Agent no longer exists")
        val catalog = tools.snapshot(conversations.getProject(task.conversationId), agent.toolAccess)
        val blocked = payload.toolCalls.filter { it.call.name !in catalog.entries }
        require(blocked.isEmpty()) {
            "Tool access was denied or the approved revision is unavailable: ${blocked.joinToString { it.call.name }}"
        }
    }
}
