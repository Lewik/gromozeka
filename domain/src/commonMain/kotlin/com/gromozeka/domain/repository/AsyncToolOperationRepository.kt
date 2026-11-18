package com.gromozeka.domain.repository

import com.gromozeka.domain.model.AsyncToolInvocation
import com.gromozeka.domain.model.AsyncToolOperation
import com.gromozeka.domain.model.AsyncToolOutcome
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import kotlin.time.Instant

interface AsyncToolOperationRepository {
    /** Same trusted source returns the original operation; changed immutable input is an error. */
    suspend fun admit(id: AsyncToolOperation.Id, invocation: AsyncToolInvocation, at: Instant): AsyncToolOperation
    suspend fun find(id: AsyncToolOperation.Id): AsyncToolOperation?
    suspend fun findBySource(conversationId: com.gromozeka.domain.model.Conversation.Id, taskId: com.gromozeka.domain.service.ConversationRuntimeTask.Id,
        callId: com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall.Id): AsyncToolOperation?
    suspend fun pendingDeliveryConversations(): Set<com.gromozeka.domain.model.Conversation.Id>
    suspend fun pendingDelivery(conversationId: com.gromozeka.domain.model.Conversation.Id,
        threadId: com.gromozeka.domain.model.Conversation.Thread.Id, limit: Int): List<AsyncToolOperation>
    suspend fun pending(phase: AsyncToolOperation.Phase, limit: Int): List<AsyncToolOperation>
    suspend fun expiredLeases(at: Instant, limit: Int): List<AsyncToolOperation>
    suspend fun unprojected(limit: Int): List<AsyncToolOperation>
    suspend fun acknowledge(id: AsyncToolOperation.Id, at: Instant): Boolean
    suspend fun claim(id: AsyncToolOperation.Id, owner: ConversationRuntimeServerSessionId, at: Instant, leaseDeadline: Instant): Boolean
    suspend fun markStarted(id: AsyncToolOperation.Id, owner: ConversationRuntimeServerSessionId, at: Instant): Boolean
    suspend fun recordWorkerDispatch(id: AsyncToolOperation.Id): Boolean
    suspend fun renewLease(id: AsyncToolOperation.Id, owner: ConversationRuntimeServerSessionId, leaseDeadline: Instant): Boolean
    suspend fun requestConversationCancellation(conversationId: com.gromozeka.domain.model.Conversation.Id, at: Instant): Int
    suspend fun requestCancellation(id: AsyncToolOperation.Id, at: Instant): Boolean
    /** CAS against the observed phase and owner. A late response cannot replace a durable unknown outcome. */
    suspend fun finish(observed: AsyncToolOperation, phase: AsyncToolOperation.Phase, outcome: AsyncToolOutcome, at: Instant): Boolean
    suspend fun markProjected(id: AsyncToolOperation.Id, at: Instant): Boolean
}
