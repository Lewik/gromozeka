package com.gromozeka.server

import com.gromozeka.domain.model.ConversationPinConflictException
import com.gromozeka.domain.model.ConversationPinLimitException
import com.gromozeka.domain.model.ConversationPinNotFoundException
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.CancellationException

internal fun ClientRequest.isConversationPinOperation(): Boolean =
    this is ListConversationPinsRequest || this is GetConversationPinRequest ||
        this is CreateConversationPinRequest || this is UpdateConversationPinRequest ||
        this is DeleteConversationPinRequest || this is CreateConversationWithCopiedPinsRequest

/** Never return or log a storage exception, its cause, or a request containing the pin text. */
internal fun conversationPinErrorResponse(error: Throwable): ErrorResponse {
    if (error is CancellationException) throw error
    val code = when (error) {
        is ConversationPinNotFoundException -> ConversationPinErrorCode.PIN_NOT_FOUND
        is ConversationPinConflictException -> ConversationPinErrorCode.PIN_CONFLICT
        is ConversationPinLimitException -> ConversationPinErrorCode.PIN_LIMIT_EXCEEDED
        is ProjectAccessDeniedException -> ConversationPinErrorCode.PIN_TARGET_UNAVAILABLE
        else -> ConversationPinErrorCode.PIN_OPERATION_FAILED
    }
    return ErrorResponse(message = code.name, type = code.name)
}
