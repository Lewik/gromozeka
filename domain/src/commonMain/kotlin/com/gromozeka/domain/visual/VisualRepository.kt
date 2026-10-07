package com.gromozeka.domain.visual

import com.gromozeka.domain.model.Conversation

interface VisualRepository {
    suspend fun list(conversationId: Conversation.Id): List<Visual>
    suspend fun find(conversationId: Conversation.Id, visualId: String): Visual?
    /** Insert at revision 1, otherwise compare-and-swap against revision - 1. */
    suspend fun save(visual: Visual)
    suspend fun delete(conversationId: Conversation.Id, visualId: String): Boolean
    suspend fun withHandlers(): List<Visual>
    suspend fun findAction(eventId: String): VisualActionReceipt?
    /** Atomically save the accepted form and reserve the action before any external effect. */
    suspend fun acceptAction(visual: Visual, receipt: VisualActionReceipt): Boolean
    suspend fun finishAction(eventId: String, result: VisualActionResult)
}

@kotlinx.serialization.Serializable
data class VisualActionReceipt(
    val eventId: String,
    val visualId: String,
    val conversationId: Conversation.Id,
    val actorUserId: com.gromozeka.domain.model.User.Id,
    val payloadHash: String,
    val createdAt: kotlin.time.Instant,
    val result: VisualActionResult? = null,
)
