package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.AsyncToolOperation.Phase
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.AsyncToolOperationRepository
import com.gromozeka.domain.service.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

internal class TestAsyncToolOperations : AsyncToolOperationRepository {
    private val mutex = Mutex()
    val records = linkedMapOf<AsyncToolOperation.Id, AsyncToolOperation>()
    val workerRequests = linkedMapOf<String, StoredWorkerRequest>()

    override suspend fun admit(id: AsyncToolOperation.Id, invocation: AsyncToolInvocation, at: Instant): AsyncToolOperation = mutex.withLock {
        val existing = records.values.singleOrNull { it.invocation.origin.let { origin ->
            origin.conversationId == invocation.origin.conversationId && origin.sourceTaskId == invocation.origin.sourceTaskId &&
                origin.sourceToolCallId == invocation.origin.sourceToolCallId } }
        if (existing != null) { require(existing.invocation == invocation); return@withLock existing }
        require(records.values.count { !it.phase.isTerminal && it.invocation.origin.actorUserId == invocation.origin.actorUserId } < 256)
        AsyncToolOperation(id, invocation, at).also { records[id] = it }
    }
    override suspend fun find(id: AsyncToolOperation.Id) = mutex.withLock { records[id] }
    override suspend fun findBySource(conversationId: Conversation.Id, taskId: ConversationRuntimeTask.Id, callId: ContentItem.ToolCall.Id) = mutex.withLock {
        records.values.singleOrNull { it.invocation.origin.let { o -> o.conversationId == conversationId && o.sourceTaskId == taskId && o.sourceToolCallId == callId } }
    }
    override suspend fun pendingDeliveryConversations() = mutex.withLock {
        records.values.filter { it.phase.isTerminal && it.acknowledgedAt != null && it.projectedAt == null }
            .mapTo(mutableSetOf()) { it.invocation.origin.conversationId }
    }
    override suspend fun pendingDelivery(conversationId: Conversation.Id, threadId: Conversation.Thread.Id, limit: Int) = mutex.withLock {
        records.values.filter { it.phase.isTerminal && it.acknowledgedAt != null && it.projectedAt == null &&
            it.invocation.origin.conversationId == conversationId && it.invocation.origin.threadId == threadId }.take(limit)
    }
    override suspend fun pending(phase: Phase, limit: Int) = mutex.withLock { require(!phase.isTerminal); records.values.filter { it.phase == phase }.take(limit) }
    override suspend fun expiredLeases(at: Instant, limit: Int) = mutex.withLock { records.values.filter { it.leaseDeadline?.let { deadline -> deadline <= at } == true }.take(limit) }
    override suspend fun unprojected(limit: Int) = mutex.withLock { records.values.filter { it.phase.isTerminal && it.acknowledgedAt != null && it.projectedAt == null }.take(limit) }
    override suspend fun acknowledge(id: AsyncToolOperation.Id, at: Instant) = change(id, { it.acknowledgedAt == null }) {
        it.copy(acknowledgedAt = at, phase = if (it.phase == Phase.ACCEPTED) Phase.ACKNOWLEDGED else it.phase)
    }
    override suspend fun claim(id: AsyncToolOperation.Id, owner: ConversationRuntimeServerSessionId, at: Instant, leaseDeadline: Instant) = change(id, {
        it.acknowledgedAt != null && it.cancelRequestedAt == null &&
            (it.phase == Phase.ACKNOWLEDGED || (it.phase == Phase.CLAIMED && checkNotNull(it.leaseDeadline) <= at))
    }) { it.copy(phase = Phase.CLAIMED, claimedAt = at, executorSessionId = owner, leaseDeadline = leaseDeadline) }
    override suspend fun markStarted(id: AsyncToolOperation.Id, owner: ConversationRuntimeServerSessionId, at: Instant) = change(id, {
        it.phase == Phase.CLAIMED && it.executorSessionId == owner && it.cancelRequestedAt == null && checkNotNull(it.leaseDeadline) > at
    }) { it.copy(phase = Phase.RUNNING, startedAt = at) }
    override suspend fun recordWorkerDispatch(id: AsyncToolOperation.Id): Boolean = mutex.withLock {
        val operation = records[id] ?: return@withLock false
        val dispatched = workerRequests[id.value]?.dispatchedAt ?: return@withLock false
        if (operation.phase != Phase.CLAIMED || operation.workerRequestCreatedAt == null) return@withLock false
        records[id] = operation.copy(phase = Phase.RUNNING, startedAt = dispatched); true
    }
    override suspend fun renewLease(id: AsyncToolOperation.Id, owner: ConversationRuntimeServerSessionId, leaseDeadline: Instant) = change(id, {
        it.phase in setOf(Phase.CLAIMED, Phase.RUNNING) && it.executorSessionId == owner
    }) { it.copy(leaseDeadline = leaseDeadline) }
    override suspend fun requestConversationCancellation(conversationId: Conversation.Id, at: Instant): Int = mutex.withLock {
        val affected = records.values.filter { it.invocation.origin.conversationId == conversationId && !it.phase.isTerminal && it.cancelRequestedAt == null }
        affected.forEach { records[it.id] = it.copy(cancelRequestedAt = at) }; affected.size
    }
    override suspend fun requestCancellation(id: AsyncToolOperation.Id, at: Instant) = change(id, { !it.phase.isTerminal }) {
        it.copy(cancelRequestedAt = it.cancelRequestedAt ?: at)
    }
    override suspend fun finish(observed: AsyncToolOperation, phase: Phase, outcome: AsyncToolOutcome, at: Instant): Boolean {
        require(!observed.phase.isTerminal && phase.isTerminal)
        observed.copy(phase = phase, completedAt = at, outcome = outcome, leaseDeadline = null)
        return change(observed.id, { it.phase == observed.phase && it.executorSessionId == observed.executorSessionId && !it.phase.isTerminal }) {
            it.copy(phase = phase, completedAt = at, outcome = outcome, leaseDeadline = null)
        }
    }
    override suspend fun markProjected(id: AsyncToolOperation.Id, at: Instant) = change(id, {
        it.phase.isTerminal && it.acknowledgedAt != null && it.projectedAt == null
    }) { it.copy(projectedAt = at) }

    private suspend fun change(id: AsyncToolOperation.Id, eligible: (AsyncToolOperation) -> Boolean,
        next: (AsyncToolOperation) -> AsyncToolOperation): Boolean = mutex.withLock {
        val current = records[id] ?: return@withLock false
        if (!eligible(current)) return@withLock false
        records[id] = next(current); true
    }
}
