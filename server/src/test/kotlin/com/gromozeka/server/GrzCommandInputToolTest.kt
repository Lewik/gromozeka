package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskInputResult
import com.gromozeka.domain.service.CommandTaskOutput
import com.gromozeka.domain.service.CommandTaskService
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import com.gromozeka.domain.tool.filesystem.SendCommandInputRequest
import com.gromozeka.infrastructure.ai.config.ToolsRegistrationConfig
import com.gromozeka.infrastructure.ai.config.TypedToolCallbackAdapter
import com.gromozeka.infrastructure.ai.tool.GrzSendCommandInputToolImpl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GrzCommandInputToolTest {
    @Test
    fun `tool forwards exact UTF8 and EOF with authenticated conversation context`() {
        val service = RecordingService()
        val tool = GrzSendCommandInputToolImpl(service)
        val text = "שלום\n{\"event\":\"refresh\"}\n\u0000"
        val result = tool.execute(
            SendCommandInputRequest("task-1", text, close_input = true),
            context(),
        )
        assertEquals(Conversation.Id("conversation-1"), service.conversationId)
        assertEquals(CommandTask.Id("task-1"), service.taskId)
        assertContentEquals(text.encodeToByteArray(), service.bytes)
        assertEquals(true, service.closeInput)
        assertEquals(mapOf("success" to true, "task_id" to "task-1", "written_bytes" to text.encodeToByteArray().size, "input_closed" to true), result)
        assertFalse(result.containsKey("text"))
        assertFalse(tool.metadata.logInput)
    }

    @Test
    fun `tool requires conversation and never invents a newline`() {
        val service = RecordingService()
        val tool = GrzSendCommandInputToolImpl(service)
        assertFailsWith<IllegalStateException> { tool.execute(SendCommandInputRequest("task-1", "x"), null) }
        assertEquals(0, service.calls)
        tool.execute(SendCommandInputRequest("task-1", "x"), context())
        assertContentEquals(byteArrayOf('x'.code.toByte()), service.bytes)
        assertEquals(false, service.closeInput)
    }

    @Test
    fun `tool allows EOF without text and exposes task owner routing`() {
        val service = RecordingService()
        val tool = GrzSendCommandInputToolImpl(service)
        tool.execute(SendCommandInputRequest("task-1", close_input = true), context())
        assertContentEquals(byteArrayOf(), service.bytes)
        assertEquals(true, service.closeInput)
        assertEquals(AiToolExecutionScope.COMMAND_TASK_OWNER, tool.metadata.executionScope)
        val callback = ToolsRegistrationConfig(TypedToolCallbackAdapter())
            .toolCallbacksRegistrar(listOf(tool)).callbacks.single()
        val schema = Json.parseToJsonElement(callback.definition.inputSchema).jsonObject
        assertEquals(setOf("task_id", "text", "close_input"), schema.getValue("properties").jsonObject.keys)
    }

    @Test
    fun `tool does not retry a failed write or hide it as success`() {
        val service = RecordingService().apply { fail = true }
        assertFailsWith<java.io.IOException> {
            GrzSendCommandInputToolImpl(service).execute(SendCommandInputRequest("task-1", "x"), context())
        }
        assertEquals(1, service.calls)
    }

    private fun context() = ToolExecutionContext(mapOf("conversationId" to "conversation-1"))

    private class RecordingService : CommandTaskService {
        var conversationId: Conversation.Id? = null
        var taskId: CommandTask.Id? = null
        var bytes = byteArrayOf()
        var closeInput = false
        var calls = 0
        var fail = false

        override suspend fun sendInput(conversationId: Conversation.Id, taskId: CommandTask.Id, bytes: ByteArray, closeInput: Boolean): CommandTaskInputResult {
            calls++
            if (fail) throw java.io.IOException("pipe closed after partial input")
            this.conversationId = conversationId
            this.taskId = taskId
            this.bytes = bytes
            this.closeInput = closeInput
            return CommandTaskInputResult(taskId, bytes.size, closeInput)
        }
        override suspend fun start(request: ExecuteCommandRequest, context: ToolExecutionContext): CommandTaskOutput = error("Unexpected start")
        override suspend fun get(conversationId: Conversation.Id, taskId: CommandTask.Id, afterByte: Long, waitMillis: Long): CommandTaskOutput? = error("Unexpected get")
        override suspend fun cancel(conversationId: Conversation.Id, taskId: CommandTask.Id): Boolean = error("Unexpected cancel")
        override suspend fun cancelAll(conversationId: Conversation.Id): Int = error("Unexpected cancelAll")
    }
}
