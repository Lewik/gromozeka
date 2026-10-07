package com.gromozeka.domain.visual

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.CommandTask

/** Server-side ports; implementations route to an exact authenticated Worker session. */
interface VisualCommandRuntime {
    suspend fun prepare(actor: User, conversation: Conversation, spec: VisualHandlerSpec): VisualHandler
    suspend fun start(actor: User, visual: Visual): CommandTask
    suspend fun sendInput(actor: User, visual: Visual, input: String)
    suspend fun cancel(visual: Visual)
    suspend fun failure(visual: Visual): String?
}

fun interface VisualInteractionDelivery {
    suspend fun send(actor: User, visual: Visual, interaction: Conversation.Message.ContentItem.VisualInteraction): Boolean
}

/** Sends a transient command to connected clients; no queue for offline clients or UI feedback. */
fun interface VisualHighlightDelivery {
    suspend fun send(userId: User.Id, command: VisualHighlightCommand): Int
}
