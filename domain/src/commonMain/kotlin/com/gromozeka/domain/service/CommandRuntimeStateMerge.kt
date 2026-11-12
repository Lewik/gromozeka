package com.gromozeka.domain.service

fun CommandTask.requireSameIdentity(other: CommandTask) {
    require(id == other.id && conversationId == other.conversationId && workerId == other.workerId &&
        workspaceMountId == other.workspaceMountId && agentDefinitionId == other.agentDefinitionId &&
        visualId == other.visualId && command == other.command && description == other.description && workingDirectory == other.workingDirectory &&
        processLifetime == other.processLifetime && processId == other.processId &&
        processStartedAt == other.processStartedAt && processTreeId == other.processTreeId &&
        outputFile == other.outputFile && slotOrigin == other.slotOrigin && timeoutAt == other.timeoutAt &&
        createdAt == other.createdAt && launchReference == other.launchReference) {
        "Command task identity or execution scope cannot change"
    }
}

fun CommandMonitor.requireSameIdentity(other: CommandMonitor) {
    require(id == other.id && conversationId == other.conversationId && commandTaskId == other.commandTaskId &&
        workerId == other.workerId && workspaceMountId == other.workspaceMountId &&
        agentDefinitionId == other.agentDefinitionId && filterCommand == other.filterCommand &&
        mode == other.mode && startFrom == other.startFrom && processId == other.processId &&
        processStartedAt == other.processStartedAt && processTreeId == other.processTreeId &&
        outputFile == other.outputFile && errorFile == other.errorFile && slotOrigin == other.slotOrigin &&
        createdAt == other.createdAt && launchReference == other.launchReference) {
        "Command monitor identity or execution scope cannot change"
    }
}

/** Invoke inside the coordinator's atomic mutation, not as an earlier gateway check. */
fun CommandTask.mergeCoordinatorState(existing: CommandTask?): CommandTask {
    if (existing == null) return this
    requireSameIdentity(existing)
    val state = if (existing.isTerminal) existing else this
    val preserveCancellationStatus =
        !state.isTerminal && cancellationRequestedAt == null && existing.cancellationRequestedAt != null
    return state.copy(
        cancellationRequestedAt = existing.cancellationRequestedAt ?: cancellationRequestedAt,
        completionNotificationRequestedAt = existing.completionNotificationRequestedAt ?: completionNotificationRequestedAt,
        completionNotificationDeliveredAt = existing.completionNotificationDeliveredAt ?: completionNotificationDeliveredAt,
        statusMessage = if (preserveCancellationStatus) existing.statusMessage else state.statusMessage,
        updatedAt = maxOf(updatedAt, existing.updatedAt),
    )
}

fun CommandMonitor.mergeCoordinatorState(existing: CommandMonitor?): CommandMonitor {
    if (existing == null) return this
    requireSameIdentity(existing)
    val state = if (existing.isTerminal) existing else this
    val preserveCancellationStatus =
        !state.isTerminal && cancellationRequestedAt == null && existing.cancellationRequestedAt != null
    return state.copy(
        cancellationRequestedAt = existing.cancellationRequestedAt ?: cancellationRequestedAt,
        terminalNotificationRequestedAt = existing.terminalNotificationRequestedAt ?: terminalNotificationRequestedAt,
        terminalNotificationDeliveredAt = existing.terminalNotificationDeliveredAt ?: terminalNotificationDeliveredAt,
        statusMessage = if (preserveCancellationStatus) existing.statusMessage else state.statusMessage,
        updatedAt = maxOf(updatedAt, existing.updatedAt),
    )
}
