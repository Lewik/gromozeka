package com.gromozeka.domain.tool

import com.gromozeka.domain.service.ConversationRuntimeCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    @Test
    fun `output schema hints and access policy are part of the immutable contract`() {
        val variants = listOf(
            descriptor.copy(definition = descriptor.definition.copy(outputSchema = """{"type":"object"}""")),
            descriptor.copy(definition = descriptor.definition.copy(annotations = AiToolAnnotations(readOnlyHint = true))),
            descriptor.copy(definition = descriptor.definition.copy(annotations = AiToolAnnotations(destructiveHint = true))),
            descriptor.copy(definition = descriptor.definition.copy(annotations = AiToolAnnotations(idempotentHint = true))),
            descriptor.copy(definition = descriptor.definition.copy(annotations = AiToolAnnotations(openWorldHint = false))),
            descriptor.copy(metadata = descriptor.metadata.copy(accessPolicy = AiToolAccessPolicy.AUTHENTICATED)),
            descriptor.copy(metadata = descriptor.metadata.copy(accessPolicy = AiToolAccessPolicy.SERVER_OWNER)),
        )
        val fingerprints = variants.map { it.contractFingerprint() }
        assertEquals(variants.size, fingerprints.toSet().size)
        fingerprints.forEach { assertNotEquals(descriptor.contractFingerprint(), it) }
    }

    @Test
    fun `output schema order is canonical and full descriptor survives serialization`() {
        val original = descriptor.copy(
            definition = descriptor.definition.copy(
                outputSchema = """{"type":"object","properties":{"ok":{"type":"boolean"}}}""",
                annotations = AiToolAnnotations(true, false, true, false),
            ),
            metadata = descriptor.metadata.copy(accessPolicy = AiToolAccessPolicy.SERVER_OWNER),
        )
        val reordered = original.copy(definition = original.definition.copy(
            outputSchema = """{"properties":{"ok":{"type":"boolean"}},"type":"object"}""",
        ))
        assertEquals(original.contractFingerprint(), reordered.contractFingerprint())
        assertEquals(original, Json.decodeFromString<AiToolDescriptor>(Json.encodeToString(original)))
    }

    @Test
    fun `structured-only outcome has model text without losing explicit error state`() {
        val structured = buildJsonObject { put("message", "failed") }
        val outcome = AiToolCallResult(structuredContent = structured, isError = true)
        assertTrue(outcome.isError)
        assertEquals(listOf(AiToolResult.Text(structured.toString())), outcome.contentForModel())
        assertEquals(structured.toString(), outcome.renderText())
    }

    @Test
    fun `mixed content and structured metadata remain unchanged in the outcome`() {
        val content = listOf(AiToolResult.Text("ok"), AiToolResult.Binary(byteArrayOf(1, 2), "image.png", "image/png"))
        val structured = buildJsonObject { put("ok", true) }
        val outcome = AiToolCallResult(content, structured)
        assertEquals(content, outcome.contentForModel())
        assertEquals(structured, outcome.structuredContent)
        assertEquals("ok", outcome.renderText())
    }

}
