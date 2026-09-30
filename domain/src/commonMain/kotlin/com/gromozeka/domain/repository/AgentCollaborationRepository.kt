package com.gromozeka.domain.repository

import com.gromozeka.domain.model.*

interface AgentCollaborationRepository {
    suspend fun requests(conversationId: Conversation.Id): List<AgentRequest>
    suspend fun findRequest(id: String): AgentRequest?
    /** Store state changes and outbound messages in the same transaction. Return false on a revision conflict. */
    suspend fun change(expected: List<AgentRequest>, updated: List<AgentRequest>, deliveries: List<AgentDelivery>): Boolean
    suspend fun create(request: AgentRequest?, delivery: AgentDelivery)
    /** Read a bounded page ordered by (createdAt, id), strictly after the last scanned delivery. */
    suspend fun pendingDeliveries(after: DeliveryCursor? = null): List<AgentDelivery>
    data class DeliveryCursor(val createdAt: kotlin.time.Instant, val id: String)
    suspend fun findDelivery(id: String): AgentDelivery?
    suspend fun settleDelivery(delivery: AgentDelivery)
    suspend fun saveDraft(draft: AgentResponseDraft)
    suspend fun findDraft(id: String): AgentResponseDraft?
    suspend fun failDraft(id: String, reason: String)
    /** Reject if a safe-point insertion arrived before commit, the branch changed, or ownership was lost. */
    suspend fun applyDecision(
        draft: AgentResponseDraft,
        activeTaskId: String,
        decision: AgentResponseDecision,
        updated: List<AgentRequest>,
        deliveries: List<AgentDelivery>,
    ): Boolean
}
