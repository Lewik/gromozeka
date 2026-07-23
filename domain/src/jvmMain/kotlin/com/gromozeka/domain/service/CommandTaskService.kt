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

data class CommandProcessRecoverySpec(
    val processId: Long?,
    val processStartedAt: Instant?,
    val processTreeId: Long?,
    val outputFile: String,
)

sealed interface CommandProcessRecovery {
    data class Running(val process: RunningCommandProcess) : CommandProcessRecovery
    data class Completed(val exitCode: Int) : CommandProcessRecovery
    data class UnrecoverableRunning(
        val process: RunningCommandProcess,
        val reason: String,
    ) : CommandProcessRecovery
    data class Unavailable(val reason: String) : CommandProcessRecovery
}

interface CommandProcessRunner {
    fun start(spec: CommandProcessSpec): RunningCommandProcess

    fun recover(spec: CommandProcessRecoverySpec): CommandProcessRecovery

    fun deleteOutputArtifacts(outputFile: String)

    fun garbageCollectOutputArtifacts(retainedOutputFiles: Set<String>)
}

interface RunningCommandProcess {
    val processId: Long
    val processStartedAt: Instant
    val processTreeId: Long
    val outputFile: String

    fun isAlive(): Boolean
    fun waitFor(timeoutMillis: Long): Boolean
    fun exitCode(): Int
    fun terminateTree()
}
