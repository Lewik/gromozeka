package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
data class CommandTask(
    val id: Id,
    val conversationId: Conversation.Id,
    val command: String,
    val workingDirectory: String,
    val status: Status,
    val processId: Long?,
    val processStartedAt: Instant?,
    val outputFile: String,
    val outputBytes: Long,
    val exitCode: Int? = null,
    val statusMessage: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    enum class Status {
        WORKING,
        COMPLETED,
        FAILED,
        CANCELLED,
    }

    val isTerminal: Boolean
        get() = status != Status.WORKING
}

@Serializable
data class CommandTaskOutput(
    val task: CommandTask,
    val output: String,
    val outputStartByte: Long,
    val nextOutputByte: Long,
    val hasMoreOutput: Boolean,
)
