package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation

interface CommandRuntimeStateService {
    suspend fun upsertCommandTask(task: CommandTask): CommandTaskUpsertResult

    suspend fun findCommandTasks(): List<CommandTask>

    suspend fun findCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): CommandTask?

    suspend fun synchronizeCommandMonitor(
        monitor: CommandMonitor,
        events: List<CommandMonitorEvent> = emptyList(),
    ): CommandMonitorSyncResult

    suspend fun findCommandMonitors(): List<CommandMonitor>

    suspend fun findCommandMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): CommandMonitor?

    suspend fun findCommandMonitorEvents(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): List<CommandMonitorEvent>

    suspend fun publishSnapshot(conversationId: Conversation.Id)
}
