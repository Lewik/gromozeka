package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC
import com.gromozeka.domain.tool.TOOL_CONTEXT_TOOL_NAME
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MemoryOperationResultDeliveryTest {
    @Test
    fun `gromozeka context derives matching prefixed status tool`() {
        val delivery = ToolExecutionContext(
            mapOf(
                TOOL_CONTEXT_CONVERSATION_ID to "conversation-1",
                TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent-1",
                TOOL_CONTEXT_TOOL_NAME to "mcp__memory__memory_answer_question",
                TOOL_CONTEXT_MEMORY_RESULT_DELIVERY to TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC,
            )
        ).memoryOperationResultDeliveryOrNull()

        assertEquals(Conversation.Id("conversation-1"), delivery?.conversationId)
        assertEquals(AgentDefinition.Id("agent-1"), delivery?.agentDefinitionId)
        assertEquals("mcp__memory__memory_run_status", delivery?.statusToolName)
    }

    @Test
    fun `external context does not enable automatic delivery`() {
        val delivery = ToolExecutionContext(
            mapOf(TOOL_CONTEXT_CONVERSATION_ID to "conversation-1")
        ).memoryOperationResultDeliveryOrNull()

        assertNull(delivery)
    }

    @Test
    fun `queued response tells gromozeka not to poll`() {
        val raw = MemoryToolResultRenderer.operationQueuedResultJsonString(
            MemoryOperationQueuedResult(
                runId = MemoryRun.Id("run-1"),
                operation = MemoryOperationKind.ENRICH_CONTEXT,
                namespace = MemoryNamespace.Global,
                queueSize = 1,
                resultDelivery = MemoryOperationResultDelivery(
                    conversationId = Conversation.Id("conversation-1"),
                    agentDefinitionId = AgentDefinition.Id("agent-1"),
                    statusToolName = "mcp__memory__memory_run_status",
                ),
            )
        )
        val result = Json.parseToJsonElement(raw).jsonObject

        assertEquals("conversation_runtime", result.getValue("result_delivery").jsonPrimitive.content)
        assertFalse(result.getValue("poll_required").jsonPrimitive.boolean)
        assertFalse(result.getValue("message").jsonPrimitive.content.contains("Call memory_run_status"))
    }
}
