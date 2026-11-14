package com.gromozeka.server

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.*

class AiCallToolTest {
    private fun tool(service: AiCallService): AiToolCallback {
        val provider = object : ObjectProvider<AiCallService> { override fun getObject() = service }
        return AiCallToolContributor(provider).callbacks.single()
    }
    private val arguments = """{"model_configuration_id":"selected","prompt":"Extract","inputs":[{"type":"text","text":"data"}]}"""

    @Test fun `descriptor is discoverable server tool with no input logging or inherited Agent requirement`() {
        val callback = tool(Mockito.mock(AiCallService::class.java))
        assertEquals("grz_ai_call", callback.definition.name)
        assertEquals(AiToolExecutionScope.SERVER, callback.metadata.executionScope)
        assertEquals(setOf(AiToolContextField.USER, AiToolContextField.CONVERSATION), callback.metadata.contextRequirements.required)
        assertFalse(callback.metadata.logInput)
        assertFalse(callback.metadata.visibleToMemoryPipeline)
        assertEquals(false, callback.definition.annotations?.idempotentHint)
        val schema = Json.parseToJsonElement(callback.definition.inputSchema).jsonObject
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals(setOf("model_configuration_id", "prompt"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test fun `invalid fields types and selectors fail before any model work`() = runBlocking {
        val service = Mockito.mock(AiCallService::class.java)
        val callback = tool(service)
        for (value in listOf("{}", arguments.replace("\"prompt\":\"Extract\"", "\"prompt\":42"),
            arguments.dropLast(1) + ",\"tools\":[\"bash\"]}", arguments.dropLast(1) + ",\"max_output_tokens\":\"12\"}",
            arguments.replace("\"text\":\"data\"", "\"text\":\"data\",\"path\":\"private\""),
            arguments.dropLast(1) + ",\"response_schema\":null}", arguments.dropLast(1) + ",\"result_mode\":\"file\"}")) {
            val result = callback.callResult(value, ToolExecutionContext())
            assertEquals(true, result.isError)
            assertFalse(result.renderText().contains("private"))
        }
        Mockito.verifyNoInteractions(service)
    }

    @Test fun `machine result status isError usage and artifact-only delivery agree`() = runBlocking {
        val f = AiCallServiceTest.Fixture()
        val callback = tool(f.service)
        val success = callback.callResult(arguments, f.context)
        assertEquals(false, success.isError)
        assertEquals(JsonPrimitive("answer"), success.structuredContent!!.jsonObject["result"]!!.jsonObject["output"])
        f.response = f.response.copy(outcome = AiStepOutcome.INCOMPLETE)
        val partial = callback.callResult(arguments, f.context)
        assertEquals(true, partial.isError)
        assertEquals(JsonPrimitive("incomplete"), partial.structuredContent!!.jsonObject["result"]!!.jsonObject["status"])
        f.response = f.response.copy(outcome = AiStepOutcome.COMPLETE, messages = f.answer("private answer not in parent"))
        val artifact = callback.callResult(arguments.dropLast(1) + ",\"result_mode\":\"artifact\"}", f.context)
        assertEquals(false, artifact.isError)
        assertFalse(artifact.renderText().contains("private answer"))
        assertNotNull(artifact.structuredContent!!.jsonObject["result"]!!.jsonObject["artifact"])
    }

    @Test fun `unexpected errors do not echo input credentials reasoning or backend bodies`() = runBlocking {
        val f = AiCallServiceTest.Fixture()
        f.execute = { throw IllegalStateException("private input credential reasoning") }
        val result = tool(f.service).callResult(arguments, f.context)
        assertEquals(true, result.isError)
        assertFalse(result.renderText().contains("private"))
        assertEquals(1, f.calls.size)
    }

    @Test fun `in flight cancellation propagates and produces neither success nor an artifact`() = runBlocking {
        val f = AiCallServiceTest.Fixture()
        val started = CompletableDeferred<Unit>()
        f.execute = { started.complete(Unit); awaitCancellation() }
        val job = launch { tool(f.service).callResult(arguments, f.context); error("Cancelled call must not return") }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(f.artifactRows.isEmpty())
        assertEquals(1, f.calls.size)
    }
}
