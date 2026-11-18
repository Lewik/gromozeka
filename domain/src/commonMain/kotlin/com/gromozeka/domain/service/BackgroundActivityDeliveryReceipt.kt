package com.gromozeka.domain.service

import kotlin.time.Instant

/** Sources published by a step; acknowledge only together with that step's durable continuation. */
data class BackgroundActivityDeliveryReceipt(
    val commands: Set<CommandTask.Id> = emptySet(),
    val monitorEvents: Set<CommandMonitorEvent.Id> = emptySet(),
    val terminalMonitors: Set<CommandMonitor.Id> = emptySet(),
    val asyncTools: Set<com.gromozeka.domain.model.AsyncToolOperation.Id> = emptySet(),
) {
    init { require(commands.isNotEmpty() || monitorEvents.isNotEmpty() || terminalMonitors.isNotEmpty() || asyncTools.isNotEmpty()) }
}

data class BackgroundActivityAcknowledgedState(
    val commands: List<CommandTask>,
    val monitors: List<CommandMonitor>,
    val events: List<CommandMonitorEvent>,
)

/** Validate command/monitor receipts; async receipts are validated against their canonical invocation store in the same coordinator transaction. */
fun acknowledgeBackgroundActivities(
    receipts: List<BackgroundActivityDeliveryReceipt>,
    commands: List<CommandTask>,
    monitors: List<CommandMonitor>,
    events: List<CommandMonitorEvent>,
    at: Instant,
): BackgroundActivityAcknowledgedState {
    val commandIds = receipts.flatMapTo(mutableSetOf()) { it.commands }
    val eventIds = receipts.flatMapTo(mutableSetOf()) { it.monitorEvents }
    val monitorIds = receipts.flatMapTo(mutableSetOf()) { it.terminalMonitors }
    require(commands.map { it.id }.containsAll(commandIds)) { "Acknowledged command is missing" }
    require(monitors.map { it.id }.containsAll(monitorIds)) { "Acknowledged monitor is missing" }
    require(events.map { it.id }.containsAll(eventIds)) { "Acknowledged monitor event is missing" }
    require(commands.filter { it.id in commandIds }.all { it.isTerminal && it.completionNotificationRequestedAt != null }) {
        "Command acknowledgement requires a requested terminal result"
    }
    require(monitors.filter { it.id in monitorIds }.all { it.isTerminal && it.terminalNotificationRequestedAt != null }) {
        "Monitor acknowledgement requires a requested terminal result"
    }
    require(events.filter { it.id in eventIds }.all { it.deliveryRequested }) { "Monitor event did not request delivery" }
    return BackgroundActivityAcknowledgedState(
        commands.map { if (it.id in commandIds && it.completionNotificationDeliveredAt == null)
            it.copy(completionNotificationDeliveredAt = at, updatedAt = maxOf(it.updatedAt, at)) else it },
        monitors.map { if (it.id in monitorIds && it.terminalNotificationDeliveredAt == null)
            it.copy(terminalNotificationDeliveredAt = at, updatedAt = maxOf(it.updatedAt, at)) else it },
        events.map { if (it.id in eventIds && it.deliveredAt == null) it.copy(deliveredAt = at) else it },
    )
}
