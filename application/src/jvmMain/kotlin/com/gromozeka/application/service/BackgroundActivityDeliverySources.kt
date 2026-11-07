package com.gromozeka.application.service

import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeSchedulingSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask

internal fun ConversationRuntimeSchedulingSnapshot.failedBackgroundActivitySourceKeys(): Set<String> =
    incidents.mapNotNullTo(mutableSetOf()) { incident ->
        (incident.task.payload as? ConversationRuntimeTask.Payload.BackgroundActivityCompletion)?.sourceKey
    }

internal fun CommandTask.notificationSourceKey(): String = "command-task:${id.value}"

internal fun CommandMonitorEvent.notificationSourceKey(): String = "command-monitor-event:${id.value}"

internal fun CommandMonitor.notificationSourceKey(): String = "command-monitor-terminal:${id.value}:$completedAt"

internal fun CommandTask.hasPendingNotification(failedSources: Set<String>): Boolean =
    isTerminal && agentDefinitionId != null &&
        completionNotificationRequestedAt != null && completionNotificationDeliveredAt == null &&
        notificationSourceKey() !in failedSources

internal fun CommandMonitorEvent.hasPendingNotification(failedSources: Set<String>): Boolean =
    deliveryRequested && deliveredAt == null && notificationSourceKey() !in failedSources

internal fun CommandMonitor.hasPendingNotification(failedSources: Set<String>): Boolean =
    isTerminal && agentDefinitionId != null &&
        terminalNotificationRequestedAt != null && terminalNotificationDeliveredAt == null &&
        notificationSourceKey() !in failedSources
