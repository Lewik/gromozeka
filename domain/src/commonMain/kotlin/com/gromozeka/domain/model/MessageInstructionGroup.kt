package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MessageInstructionGroup(
    val id: String,
    val title: String,
    val controls: List<Control>,
    val selectedByDefault: Int = 0,
    val showInComposer: Boolean = false,
    val retentionMode: RetentionMode = RetentionMode.KEEP_HISTORY,
) {
    init {
        require(id.isNotBlank()) { "Message instruction group id must not be blank" }
        require(title.isNotBlank()) { "Message instruction group title must not be blank" }
        require(controls.isNotEmpty()) { "Message instruction group must contain at least one control" }
        require(selectedByDefault in controls.indices) { "Default message instruction control index is out of bounds" }
        require(controls.map { it.data.id }.distinct().size == controls.size) {
            "Message instruction ids must be unique within a group"
        }
        require(retentionMode != RetentionMode.STICKY_LATEST || controls.all(Control::includeInMessage)) {
            "Sticky message instruction groups cannot contain controls excluded from messages"
        }
    }

    @Serializable
    enum class RetentionMode {
        KEEP_HISTORY,
        STICKY_LATEST,
    }

    @Serializable
    data class Control(
        val data: Conversation.Message.Instruction.UserInstruction,
        val shortLabel: String,
        val includeInMessage: Boolean = true,
        val textShortcutAliases: List<String> = defaultTextShortcutAliases(data.id),
    ) {
        init {
            require(shortLabel.isNotBlank()) { "Message instruction short label must not be blank" }
            require(textShortcutAliases.all { alias -> alias.isNotBlank() && alias.none(Char::isWhitespace) }) {
                "Message instruction text shortcut aliases must not be blank or contain whitespace"
            }
            require(textShortcutAliases.distinctBy(String::lowercase).size == textShortcutAliases.size) {
                "Message instruction text shortcut aliases must be unique ignoring case"
            }
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
                            description = "Readonly mode - do not modify code or run commands that make changes",
                        ),
                        shortLabel = "R",
                    ),
                    Control(
                        data = Conversation.Message.Instruction.UserInstruction(
                            id = "mode_writable",
                            title = "Writable",
                            description = "File changes are allowed",
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

private fun defaultTextShortcutAliases(instructionId: String): List<String> = when (instructionId) {
    "mode_readonly" -> listOf("r", "к")
    "mode_writable" -> listOf("w", "ц")
    else -> emptyList()
}
