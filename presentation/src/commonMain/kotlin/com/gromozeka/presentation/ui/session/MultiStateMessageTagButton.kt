package com.gromozeka.presentation.ui.session

import androidx.compose.runtime.Composable
import com.gromozeka.presentation.ui.CustomSegmentedButtonGroup
import com.gromozeka.presentation.ui.SegmentedButtonOption
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageTagDefinition

@Composable
fun MultiStateMessageTagButton(
    messageTag: MessageTagDefinition,
    activeMessageTags: Set<String>,
    onToggleTag: (MessageTagDefinition, Int) -> Unit,
) {
    // Find which control is currently active based on activeMessageTags
    val activeControlIndex = messageTag.controls.indexOfFirst { control ->
        (control.data as Conversation.Message.Instruction.UserInstruction).id in activeMessageTags
    }
    val selectedIndex = if (activeControlIndex >= 0) activeControlIndex else messageTag.selectedByDefault

    // Convert MessageTagDefinition.Controls to SegmentedButtonOptions
    val options = messageTag.controls.map { control ->
        SegmentedButtonOption(
            text = (control.data as Conversation.Message.Instruction.UserInstruction).title,
            tooltip = (control.data as Conversation.Message.Instruction.UserInstruction).description
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