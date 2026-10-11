package com.gromozeka.server

import com.gromozeka.application.service.AgentContextQueryService
import com.gromozeka.application.service.ContextSnapshotUnavailableException
import com.gromozeka.application.service.ConversationContextSnapshot
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.*
import kotlin.time.Instant

class AgentContextQueryToolTest {
    private val service = Mockito.mock(AgentContextQueryService::class.java)
    @Suppress("UNCHECKED_CAST")
    private val provider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<AgentContextQueryService>
    private val tool = AgentContextQueryToolContributor(provider).callbacks.single()
    private val context = ToolExecutionContext()

    @Test fun `descriptor is lazy server-only excluded from external channels and memory`() {
        assertEquals(AGENT_CONTEXT_QUERY_TOOL_NAME, tool.definition.name)
        assertTrue(tool.definition.name in AGENT_COLLABORATION_TOOL_NAMES)
        assertEquals(AiToolExecutionScope.SERVER, tool.metadata.executionScope)
        assertFalse(tool.metadata.visibleToMemoryPipeline)
        assertFalse(tool.metadata.logInput)
        val schema = Json.parseToJsonElement(tool.definition.inputSchema).jsonObject
        assertEquals(setOf("target_conversation_id", "target_agent_id", "question"), schema.getValue("properties").jsonObject.keys)
        assertEquals(setOf("target_conversation_id", "question"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
        Mockito.verifyNoInteractions(provider, service)
    }

    @Test fun `invalid or action-like inputs never initialize snapshot service`() {
        for (input in listOf("{}", """{"target_conversation_id":12,"question":"Q"}""",
            """{"target_conversation_id":"target","question":" "}""",
            """{"target_conversation_id":"target","question":"Q","trigger_run":true}""",
            """{"target_conversation_id":"target","question":"Q","target_agent_id":false}""")) {
            assertFailsWith<IllegalArgumentException> { tool.call(input, context) }
        }
        Mockito.verifyNoInteractions(provider, service)
    }

    @Test fun `service failures stay failures without leaking private payload cause or suppressed data`() = runBlocking<Unit> {
        val privatePayload = "PRIVATE_CONTEXT_SENTINEL PRIVATE_REASONING_SENTINEL PRIVATE_CREDENTIAL_SENTINEL"
        val failure = IllegalStateException(privatePayload, IllegalArgumentException(privatePayload))
        failure.addSuppressed(UnsupportedOperationException(privatePayload))
        Mockito.`when`(provider.getObject()).thenReturn(service)
        Mockito.doThrow(failure).`when`(service).query(context, "target", "Q", null)
        val error = assertFailsWith<IllegalStateException> {
            tool.callResult("""{"target_conversation_id":"target","question":"Q"}""", context)
        }
        assertEquals("Context query failed (CONTEXT_QUERY_FAILED).", error.message)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        for (sentinel in listOf("PRIVATE_CONTEXT_SENTINEL", "PRIVATE_REASONING_SENTINEL", "PRIVATE_CREDENTIAL_SENTINEL")) {
            assertFalse(sentinel in error.stackTraceToString())
            assertFalse(sentinel in "Error: ${error.message ?: error::class.simpleName}")
        }
        Mockito.verify(service).query(context, "target", "Q", null)
        Mockito.verifyNoMoreInteractions(service)
    }

    @Test fun `snapshot and unsupported failures expose stable categories only`() = runBlocking<Unit> {
        Mockito.`when`(provider.getObject()).thenReturn(service)
        val failures = listOf(
            ContextSnapshotUnavailableException("PRIVATE_CONTEXT_SENTINEL", IllegalStateException("PRIVATE_REASONING_SENTINEL")) to
                "Context snapshot is unavailable (CONTEXT_SNAPSHOT_UNAVAILABLE).",
            UnsupportedOperationException("PRIVATE_REASONING_SENTINEL", IllegalArgumentException("PRIVATE_CONTEXT_SENTINEL")) to
                "Context query is unsupported (CONTEXT_QUERY_UNSUPPORTED).",
        )
        for ((failure, expectedMessage) in failures) {
            Mockito.doThrow(failure).`when`(service).query(context, "target", "Q", null)
            val error = assertFailsWith<IllegalStateException> {
                tool.callResult("""{"target_conversation_id":"target","question":"Q"}""", context)
            }
            assertEquals(expectedMessage, error.message)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertFalse("PRIVATE_CONTEXT_SENTINEL" in error.stackTraceToString())
            assertFalse("PRIVATE_REASONING_SENTINEL" in error.stackTraceToString())
        }
        Mockito.verify(service, Mockito.times(failures.size)).query(context, "target", "Q", null)
        Mockito.verifyNoMoreInteractions(service)
    }

    @Test fun `lazy service resolution failures are also content-free`() {
        Mockito.`when`(provider.getObject()).thenThrow(IllegalStateException("PRIVATE_CONTEXT_SENTINEL PRIVATE_REASONING_SENTINEL"))
        val error = assertFailsWith<IllegalStateException> {
            tool.callResult("""{"target_conversation_id":"target","question":"Q"}""", context)
        }
        assertEquals("Context query failed (CONTEXT_QUERY_FAILED).", error.message)
        assertNull(error.cause)
        Mockito.verify(provider).getObject()
        Mockito.verifyNoMoreInteractions(provider)
        Mockito.verifyNoInteractions(service)
    }

    @Test fun `cancellation propagates unchanged instead of becoming an operational failure`() = runBlocking<Unit> {
        val cancelled = CancellationException("cancelled query")
        Mockito.`when`(provider.getObject()).thenReturn(service)
        Mockito.doThrow(cancelled).`when`(service).query(context, "target", "Q", null)
        val error = assertFailsWith<CancellationException> {
            tool.callResult("""{"target_conversation_id":"target","question":"Q"}""", context)
        }
        assertSame(cancelled, error)
        Mockito.verify(service).query(context, "target", "Q", null)
        Mockito.verifyNoMoreInteractions(service)
    }

    @Test fun `result contains final answer and bounded snapshot provenance not the target request`() = runBlocking {
        val snapshot = ConversationContextSnapshot(
            target = AgentEndpoint(Conversation.Id("target"), Conversation.Thread.Id("pinned"), AgentDefinition.Id("receiver")),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")),
            resolvedRuntime = Mockito.mock(ResolvedAiRuntime::class.java),
            request = AiRuntimeRequest(listOf("private system prefix"), emptyList()),
            capturedAt = Instant.fromEpochMilliseconds(0), sourceMessageCount = 3, omittedTrailingMessageCount = 2,
        )
        Mockito.`when`(provider.getObject()).thenReturn(service)
        Mockito.`when`(service.query(context, "target", "Q", null)).thenReturn(AgentContextQueryService.Result("Not enough information", snapshot))
        val result = tool.call("""{"target_conversation_id":"target","question":"Q"}""", context)
        val json = Json.parseToJsonElement(result).jsonObject
        assertEquals("Not enough information", json.getValue("answer").jsonPrimitive.content)
        assertEquals("pinned", json.getValue("snapshot").jsonObject.getValue("thread_id").jsonPrimitive.content)
        assertFalse("private system prefix" in result)
        assertFalse(json.getValue("snapshot").jsonObject.getValue("globally_atomic").jsonPrimitive.boolean)
        Mockito.verify(service).query(context, "target", "Q", null)
        Mockito.verifyNoMoreInteractions(service)
    }
}
