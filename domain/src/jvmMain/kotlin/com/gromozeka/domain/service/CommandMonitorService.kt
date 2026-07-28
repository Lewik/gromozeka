package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.tool.ToolExecutionContext

const val MAX_COMMAND_MONITOR_WAIT_MILLIS = 300_000L

interface CommandMonitorService {
    suspend fun start(
        spec: CommandMonitorSpec,
        context: ToolExecutionContext,
    ): CommandMonitor

    suspend fun get(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
        afterByte: Long,
        waitMillis: Long,
    ): CommandMonitorOutput?

    suspend fun cancel(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): Boolean
}

data class CommandMonitorSpec(
    val commandTaskId: CommandTask.Id,
    val filterCommand: String,
    val mode: CommandMonitor.Mode,
    val startFrom: CommandMonitor.StartFrom,
) {
    init {
        require(filterCommand.isNotBlank()) { "Command monitor filter command must not be blank" }
    }
}
