package com.gromozeka.shared.domain.message

import kotlinx.serialization.Serializable

@Serializable
data class MessageTagDefinition(
    val controls: List<Control>,
    val selectedByDefault: Int = 0,
) {
    @Serializable
    data class Control(
        val data: ChatMessage.Instruction,
        val includeInMessage: Boolean = true,
    )
}