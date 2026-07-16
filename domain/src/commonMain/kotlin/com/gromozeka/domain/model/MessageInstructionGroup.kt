package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MessageInstructionGroup(
    val id: String,
    val title: String,
    val controls: List<Control>,
    val selectedByDefault: Int = 0,
    val showInComposer: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Message instruction group id must not be blank" }
        require(title.isNotBlank()) { "Message instruction group title must not be blank" }
        require(controls.isNotEmpty()) { "Message instruction group must contain at least one control" }
        require(selectedByDefault in controls.indices) { "Default message instruction control index is out of bounds" }
        require(controls.map { it.data.id }.distinct().size == controls.size) {
            "Message instruction ids must be unique within a group"
        }
    }

    @Serializable
    data class Control(
        val data: Conversation.Message.Instruction.UserInstruction,
        val shortLabel: String,
        val includeInMessage: Boolean = true,
    ) {
        init {
            require(shortLabel.isNotBlank()) { "Message instruction short label must not be blank" }
        }
    }

    companion object {
        fun defaults(): List<MessageInstructionGroup> = listOf(
            MessageInstructionGroup(
                id = "write_access",
                title = "Write access",
                controls = listOf(
                    Control(
                        data = Conversation.Message.Instruction.UserInstruction(
                            id = "mode_readonly",
                            title = "Readonly",
                            description = "Режим readonly - никаких изменений кода или команд применяющих изменения",
                        ),
                        shortLabel = "R",
                    ),
                    Control(
                        data = Conversation.Message.Instruction.UserInstruction(
                            id = "mode_writable",
                            title = "Writable",
                            description = "Разрешено исправление файлов",
                        ),
                        shortLabel = "W",
                    ),
                ),
                selectedByDefault = 0,
                showInComposer = true,
            )
        )
    }
}
