package com.gromozeka.domain.tool

import com.gromozeka.domain.service.ConversationRuntimeCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AiToolContractTest {
    private val descriptor = AiToolDescriptor(
        definition = AiToolDefinition(
            name = "test_tool",
            description = "Run the test tool.",
            inputSchema = """{"type":"object","properties":{"value":{"type":"string","description":"Value."}},"required":["value"]}""",
            source = "test",
        ),
        metadata = AiToolMetadata(
            returnDirect = false,
            requiredRuntimeCapabilities = setOf(
                ConversationRuntimeCapability.LOCAL_AGENT_TOOL,
                ConversationRuntimeCapability.TOOL_EXECUTION,
            ),
            executionScope = AiToolExecutionScope.WORKER,
            loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE,
        ),
    )

    @Test
    fun `fingerprint includes semantic documentation`() {
        assertNotEquals(
            descriptor.contractFingerprint(),
            descriptor.copy(
                definition = descriptor.definition.copy(description = "Run a changed test tool.")
            ).contractFingerprint(),
        )
        assertNotEquals(
            descriptor.contractFingerprint(),
            descriptor.copy(
                definition = descriptor.definition.copy(
                    inputSchema = """{"type":"object","properties":{"value":{"type":"string","description":"Changed value."}},"required":["value"]}"""
                )
            ).contractFingerprint(),
        )
    }

    @Test
    fun `fingerprint canonicalizes JSON objects and capability order`() {
        val reordered = descriptor.copy(
            definition = descriptor.definition.copy(
                inputSchema = """{"required":["value"],"properties":{"value":{"description":"Value.","type":"string"}},"type":"object"}"""
            ),
            metadata = descriptor.metadata.copy(
                requiredRuntimeCapabilities = linkedSetOf(
                    ConversationRuntimeCapability.TOOL_EXECUTION,
                    ConversationRuntimeCapability.LOCAL_AGENT_TOOL,
                )
            ),
        )

        assertEquals(descriptor.contractFingerprint(), reordered.contractFingerprint())
    }
}
