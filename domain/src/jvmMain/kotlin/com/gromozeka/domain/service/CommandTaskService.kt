package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import kotlinx.datetime.Instant

interface CommandTaskService {
    suspend fun start(
        request: ExecuteCommandRequest,
        context: ToolExecutionContext,
    ): CommandTaskOutput

    suspend fun get(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
        afterByte: Long,
        waitMillis: Long,
    ): CommandTaskOutput?

    suspend fun cancel(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): Boolean

    suspend fun cancelAll(conversationId: Conversation.Id): Int
}

data class CommandProcessSpec(
    val taskId: CommandTask.Id,
    val command: String,
    val workingDirectory: String,
)

interface CommandProcessRunner {
    fun start(spec: CommandProcessSpec): RunningCommandProcess

    fun reconnect(
        processId: Long,
        processStartedAt: Instant,
        outputFile: String,
    ): RunningCommandProcess?
}

interface RunningCommandProcess {
    val processId: Long
    val processStartedAt: Instant
    val outputFile: String

    fun isAlive(): Boolean
    fun waitFor(timeoutMillis: Long): Boolean
    fun exitCode(): Int
    fun terminateTree()
}
