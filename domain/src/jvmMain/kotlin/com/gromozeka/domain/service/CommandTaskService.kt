package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import kotlin.time.Instant

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

    /**
     * Writes one bounded byte sequence to a live process and optionally sends EOF.
     * Calls on the same process are serialized. No framing or newline is added.
     * Success acknowledges pipe I/O, not application-level processing. An interrupted
     * or failed write can have partial effects and must not be retried automatically.
     * Recovered processes without their original stdin handle reject input.
     */
    suspend fun sendInput(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
        bytes: ByteArray,
        closeInput: Boolean = false,
    ): CommandTaskInputResult
}

const val MAX_COMMAND_INPUT_BYTES = 64 * 1024

data class CommandTaskInputResult(
    val taskId: CommandTask.Id,
    val writtenBytes: Int,
    val inputClosed: Boolean,
)

data class CommandProcessSpec(
    val executionId: String,
    val command: String,
    val workingDirectory: String,
    val environment: Map<String, String> = emptyMap(),
    val captureStandardErrorSeparately: Boolean = false,
    val lifetime: CommandTask.ProcessLifetime = CommandTask.ProcessLifetime.WORKER_BOUND,
) {
    init {
        require(executionId.isNotBlank()) { "Command process execution id must not be blank" }
        require(command.isNotBlank()) { "Command process command must not be blank" }
        require(environment.keys.all(ENVIRONMENT_NAME_PATTERN::matches)) {
            "Command environment contains an invalid variable name"
        }
    }

    private companion object {
        val ENVIRONMENT_NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

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

    fun garbageCollectOutputArtifacts(
        spec: CommandOutputGarbageCollectionSpec,
    ): CommandOutputGarbageCollectionResult
}

data class CommandOutputGarbageCollectionSpec(
    val referencedOutputFiles: Set<String>,
    val protectedOutputFiles: Set<String>,
    val expireBefore: Instant,
    val maxTotalBytes: Long,
) {
    init {
        require(referencedOutputFiles.containsAll(protectedOutputFiles)) {
            "Protected command outputs must also be referenced"
        }
        require(maxTotalBytes >= 0) { "Command output byte quota must be non-negative" }
    }
}

data class CommandOutputGarbageCollectionResult(
    val deletedOutputFiles: Set<String>,
    val retainedBytes: Long,
    val protectedBytes: Long,
)

interface RunningCommandProcess {
    val processId: Long
    val processStartedAt: Instant
    val processTreeId: Long
    val outputFile: String
    val errorFile: String?
    val acceptsInput: Boolean

    fun isAlive(): Boolean
    fun waitFor(timeoutMillis: Long): Boolean
    fun exitCode(): Int
    fun writeInput(bytes: ByteArray)
    fun closeInput()
    fun terminateTree()
}
