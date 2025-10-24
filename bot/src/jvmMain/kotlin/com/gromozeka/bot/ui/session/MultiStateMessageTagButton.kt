package com.gromozeka.bot.ui.session

import androidx.compose.runtime.Composable
import com.gromozeka.bot.ui.CustomSegmentedButtonGroup
import com.gromozeka.bot.ui.SegmentedButtonOption
import com.gromozeka.shared.domain.conversation.ConversationTree
import com.gromozeka.shared.domain.message.MessageTagDefinition

@Composable
fun MultiStateMessageTagButton(
    messageTag: MessageTagDefinition,
    activeMessageTags: Set<String>,
    onToggleTag: (MessageTagDefinition, Int) -> Unit,
) {
    // Find which control is currently active based on activeMessageTags
    val activeControlIndex = messageTag.controls.indexOfFirst { control ->
        (control.data as ConversationTree.Message.Instruction.UserInstruction).id in activeMessageTags
    }
    val selectedIndex = if (activeControlIndex >= 0) activeControlIndex else messageTag.selectedByDefault

    // Convert MessageTagDefinition.Controls to SegmentedButtonOptions
    val options = messageTag.controls.map { control ->
        SegmentedButtonOption(
            text = (control.data as ConversationTree.Message.Instruction.UserInstruction).title,
            tooltip = (control.data as ConversationTree.Message.Instruction.UserInstruction).description
        )
    }

    CustomSegmentedButtonGroup(
        options = options,
        selectedIndex = selectedIndex,
        onSelectionChange = { controlIndex ->
            onToggleTag(messageTag, controlIndex)
        }
    )
}