package com.gromozeka.client

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationPin
import com.gromozeka.domain.model.ConversationPinSource
import com.gromozeka.domain.model.ConversationPinNotFoundException
import com.gromozeka.domain.model.ConversationPinConflictException
import com.gromozeka.domain.model.ConversationPinLimitException
import com.gromozeka.domain.model.ConversationPinOperationFailedException
import com.gromozeka.domain.service.ConversationPinService
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException

internal class RemoteConversationPinService(private val client: GromozekaWsClient) : ConversationPinService {
    override fun observe(conversationId: Conversation.Id): Flow<List<ConversationPin>> =
        client.observeDeclarativeState(RemoteDeclarativeStateResource.CONVERSATION_PINS, conversationId.value) {
            list(conversationId)
        }

    override suspend fun list(conversationId: Conversation.Id): List<ConversationPin> =
        client.requestConversationPin<ConversationPinsResponse>(ListConversationPinsRequest(conversationId)).pins

    override suspend fun get(conversationId: Conversation.Id, pinId: ConversationPin.Id): ConversationPin? =
        client.requestConversationPin<ConversationPinResponse>(GetConversationPinRequest(conversationId, pinId)).pin

    override suspend fun create(
        conversationId: Conversation.Id,
        text: String,
        source: ConversationPinSource?,
        creationKey: String?,
    ): ConversationPin = client.requestConversationPin<ConversationPinResponse>(
        CreateConversationPinRequest(conversationId, text, source, creationKey),
    ).pin ?: throw ConversationPinOperationFailedException()

    override suspend fun update(
        conversationId: Conversation.Id,
        pinId: ConversationPin.Id,
        expectedRevision: Long,
        text: String,
    ): ConversationPin = client.requestConversationPin<ConversationPinResponse>(
        UpdateConversationPinRequest(conversationId, pinId, expectedRevision, text),
    ).pin ?: throw ConversationPinOperationFailedException()

    override suspend fun delete(
        conversationId: Conversation.Id,
        pinId: ConversationPin.Id,
        expectedRevision: Long,
    ) {
        val result = client.requestConversationPin<OperationResultResponse>(DeleteConversationPinRequest(conversationId, pinId, expectedRevision))
        if (!result.success) throw ConversationPinOperationFailedException()
    }

}

/** Pin-specific transport boundary, shared with the copy-only Restart RPC. Never echo an unexpected response. */
internal suspend inline fun <reified T : ServerResponse> GromozekaWsClient.requestConversationPin(payload: ClientRequest): T {
    val response = try { request(payload) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw ConversationPinOperationFailedException() }
    return when (response) {
        is ErrorResponse -> throw when (response.type) {
            ConversationPinErrorCode.PIN_NOT_FOUND.name -> ConversationPinNotFoundException()
            ConversationPinErrorCode.PIN_CONFLICT.name -> ConversationPinConflictException()
            ConversationPinErrorCode.PIN_LIMIT_EXCEEDED.name -> ConversationPinLimitException()
            ConversationPinErrorCode.PIN_TARGET_UNAVAILABLE.name -> ProjectAccessDeniedException()
            else -> ConversationPinOperationFailedException()
        }
        is T -> response
        else -> throw ConversationPinOperationFailedException()
    }
}
