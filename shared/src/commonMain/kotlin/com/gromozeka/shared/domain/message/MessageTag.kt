package com.gromozeka.shared.domain.message

import com.gromozeka.shared.domain.conversation.ConversationTree
import kotlinx.serialization.Serializable

@Serializable
data class MessageTagDefinition(
    val controls: List<Control>,
    val selectedByDefault: Int = 0,
) {
    @Serializable
    data class Control(
        val data: ConversationTree.Message.Instruction,
        val includeInMessage: Boolean = true,
    )
}