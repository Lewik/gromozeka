package com.gromozeka.domain.tool

import com.gromozeka.domain.tool.AiToolContextField.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.test.*

class AiToolInvocationContextTest {
    private val server = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER)
    private val rename = server.withContextRequirements(USER, CONVERSATION)
    private val file = AiToolMetadata(executionScope = AiToolExecutionScope.WORKSPACE)
    private val worker = AiToolMetadata(executionScope = AiToolExecutionScope.WORKER)
    private val noBindings = AiToolContextBindings()
    private fun descriptor(metadata: AiToolMetadata) = AiToolDescriptor(
        AiToolDefinition("test", "Test", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}"""), metadata)
    private fun schema(metadata: AiToolMetadata, bindings: AiToolContextBindings = noBindings) =
        Json.parseToJsonElement(descriptor(metadata).withInvocationContext(bindings).definition.inputSchema).jsonObject
    private fun envelope(metadata: AiToolMetadata, bindings: AiToolContextBindings = noBindings) =
        schema(metadata, bindings).getValue("properties").jsonObject.getValue("_grz").jsonObject
    private fun parse(metadata: AiToolMetadata, input: String, bound: ToolExecutionContext? = null) =
        metadata.parseInvocationInput(Json.parseToJsonElement(input), bound)

    @Test fun renameRequiresConversationOnlyOutsideBoundRuntime() {
        assertEquals(setOf("conversation_id"), envelope(rename).getValue("properties").jsonObject.keys)
        assertEquals(listOf(JsonPrimitive("conversation_id")), envelope(rename).getValue("required").jsonArray.toList())
        assertEquals(setOf("text", "_grz"), schema(rename).getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFalse("_grz" in schema(rename, AiToolContextBindings.ConversationRuntime).getValue("properties").jsonObject)
    }

    @Test fun fileNeedsMountForInternalAndExternalCallsUnlessExplicitlyBound() {
        for (bindings in listOf(noBindings, AiToolContextBindings.ConversationRuntime)) {
            val value = envelope(file, bindings)
            assertEquals(listOf(JsonPrimitive("workspace_mount_id")), value.getValue("required").jsonArray.toList())
            assertFalse("worker_id" in value.getValue("properties").jsonObject)
        }
        val bound = ToolExecutionContext(mapOf(TOOL_CONTEXT_WORKSPACE_MOUNT_ID to "mount"))
        assertFalse("required" in envelope(file, AiToolContextBindings.from(bound)))
        assertEquals("mount", parse(file, "{}", bound).executionTarget?.workspaceMountId?.value)
        assertFailsWith<IllegalArgumentException> { parse(file, """{"_grz":{"workspace_mount_id":"other"}}""", bound) }
        assertFails { parse(file, "{}", ToolExecutionContext(mapOf(TOOL_CONTEXT_CONVERSATION_ID to "conversation"))) }
    }

    @Test fun ownerCatalogHasNoEnvelopeAndUserIsNeverSelectable() {
        val catalog = server.copy(accessPolicy = AiToolAccessPolicy.SERVER_OWNER).withContextRequirements(USER)
        assertFalse("_grz" in schema(catalog).getValue("properties").jsonObject)
        assertFailsWith<IllegalArgumentException> { parse(catalog, """{"_grz":{"user_id":"owner"}}""") }
        assertFailsWith<IllegalArgumentException> { catalog.requireInvocationContext(null) }
        catalog.requireInvocationContext(ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to "authenticated")))
    }

    @Test fun noDuplicateProjectOrWorkerSelectorRequired() {
        val scoped = server.withContextRequirements(PROJECT, CONVERSATION, AGENT, THREAD)
        assertEquals(setOf("conversation_id", "agent_id"), envelope(scoped).getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(listOf(JsonPrimitive("workspace_mount_id")), envelope(file.withContextRequirements(PROJECT)).getValue("required").jsonArray.toList())
        assertEquals(setOf("project_id", "conversation_id", "agent_id"), envelope(scoped).getValue("properties").jsonObject.keys)
    }

    @Test fun conditionalContextDoesNotBecomeAnUnconditionalRequirement() {
        val visual = server.withContextRequirements(USER, optional = setOf(CONVERSATION, AGENT))
        visual.requireInvocationContext(ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to "user")))
        assertFalse("required" in envelope(visual))
        assertFalse("_grz" in schema(visual).getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun parserPreservesBusinessTargetAndLiteralText() {
        val original = """{"conversation_id":"business-target","text":"  אבג\r\n🙂  ","_grz":{"conversation_id":"calling-context"}}"""
        val result = parse(rename, original)
        assertEquals("calling-context", result.selectors[CONVERSATION])
        assertEquals("business-target", result.arguments.getValue("conversation_id").jsonPrimitive.content)
        assertEquals("  אבג\r\n🙂  ", result.arguments.getValue("text").jsonPrimitive.content)
        assertFalse("_grz" in result.arguments)
        assertFailsWith<IllegalArgumentException> { result.requireResolvedSelectors(ToolExecutionContext()) }
    }

    @Test fun cannotOverrideBoundConversationProjectOrAgent() {
        val metadata = server.withContextRequirements(CONVERSATION, PROJECT, AGENT)
        val bound = ToolExecutionContext(mapOf(TOOL_CONTEXT_CONVERSATION_ID to "c", TOOL_CONTEXT_PROJECT_ID to "p", TOOL_CONTEXT_AGENT_DEFINITION_ID to "a"))
        for (field in listOf(CONVERSATION, PROJECT, AGENT)) {
            assertFailsWith<IllegalArgumentException> { parse(metadata, """{"_grz":{"${field.selector}":"other"}}""", bound) }
        }
        parse(metadata, """{"_grz":{"conversation_id":"c","project_id":"p","agent_id":"a"}}""", bound).requireResolvedSelectors(bound)
    }

    @Test fun rejectsOldNestedUnknownTrustedAndWrongTypedFields() {
        for (input in listOf(
            """{"execution_target":{"workspace_mount_id":"m"}}""",
            """{"_grz":{"execution_target":{"workspace_mount_id":"m"}}}""",
            """{"_grz":{"workspace_mount_id":"m","worker_id":"w"}}""",
            """{"_grz":{"workspace_mount_id":"m","workspace_root_path":"/etc"}}""",
            """{"_grz":{"workspace_mount_id":"m","memory_namespace":"other"}}""",
            """{"_grz":{"workspace_mount_id":"m","toolCallId":"trusted"}}""",
            """{"_grz":{"workspace_mount_id":"m","thread_id":"other"}}""",
            """{"_grz":{"workspace_mount_id":42}}""",
            """{"_grz":{"workspace_mount_id":null}}""",
            """{"_grz":{"workspace_mount_id":" "}}""",
            """{"_grz":null}""", """{"_grz":[]}""", "[]",
        )) assertFails(input) { parse(file, input) }
    }

    @Test fun ownerRoutedToolsCannotBeRetargeted() {
        for (scope in listOf(AiToolExecutionScope.COMMAND_TASK_OWNER, AiToolExecutionScope.COMMAND_MONITOR_OWNER)) {
            val metadata = AiToolMetadata(executionScope = scope).withContextRequirements(CONVERSATION)
            assertNull(parse(metadata, """{"task_id":"task","_grz":{"conversation_id":"c"}}""").executionTarget)
            for (key in listOf("worker_id", "workspace_mount_id", "delivery_ttl_seconds"))
                assertFailsWith<IllegalArgumentException> { parse(metadata, """{"_grz":{"$key":"other"}}""") }
        }
    }

    @Test fun timingDefaultsLimitsAndWorkerBindingAreShared() {
        val bound = ToolExecutionContext(mapOf(TOOL_CONTEXT_WORKER_ID to "w"))
        val target = parse(worker, """{"_grz":{"delivery_ttl_seconds":604800}}""", bound).executionTarget!!
        assertEquals("w", target.workerId?.value)
        assertEquals(1_800_000L, target.requestPolicy?.executionTimeoutMillis)
        assertEquals(604_800_000L, target.requestPolicy?.waitTimeoutMillis)
        for ((field, value) in listOf("delivery_ttl_seconds" to "604801", "execution_timeout_seconds" to "86401", "wait_timeout_seconds" to "null", "wait_timeout_seconds" to "1.5", "wait_timeout_seconds" to "\"3\""))
            assertFailsWith<IllegalArgumentException> { parse(worker, """{"_grz":{"$field":$value}}""", bound) }
    }

    @Test fun namespaceAndCallIdentityMustComeFromTrustedContext() {
        val metadata = server.withContextRequirements(MEMORY_NAMESPACE, TOOL_CALL_ID)
        assertFailsWith<IllegalArgumentException> { metadata.requireInvocationContext(ToolExecutionContext()) }
        metadata.requireInvocationContext(ToolExecutionContext(mapOf(TOOL_CONTEXT_MEMORY_NAMESPACE to "authorized", "toolCallId" to "trusted")))
        assertFalse("_grz" in schema(metadata).getValue("properties").jsonObject)
    }

    @Test fun contextRequirementsRoundtripAndAffectCanonicalFingerprint() {
        val base = descriptor(server)
        val required = base.copy(metadata = server.withContextRequirements(USER, CONVERSATION))
        val optional = base.copy(metadata = server.withContextRequirements(USER, optional = setOf(CONVERSATION)))
        assertEquals(required, Json.decodeFromString<AiToolDescriptor>(Json.encodeToString(required)))
        assertNotEquals(base.contractFingerprint(), required.contractFingerprint())
        assertNotEquals(required.contractFingerprint(), optional.contractFingerprint())
        assertEquals(required.contractFingerprint(), base.copy(metadata = server.withContextRequirements(CONVERSATION, USER)).contractFingerprint())
    }

    @Test fun businessSchemasCannotClaimReservedEnvelope() {
        for (name in listOf("_grz", "execution_target")) {
            val value = descriptor(server).copy(definition = descriptor(server).definition.copy(inputSchema = """{"type":"object","properties":{"$name":{"type":"object"}}}"""))
            assertFailsWith<IllegalArgumentException> { value.withInvocationContext(noBindings) }
        }
    }
}
