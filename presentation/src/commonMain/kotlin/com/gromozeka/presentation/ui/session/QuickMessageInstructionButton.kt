package com.gromozeka.presentation.ui.session

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.presentation.ui.CompactButton

@Composable
fun QuickMessageInstructionButton(
    group: MessageInstructionGroup,
    activeInstructionIds: Set<String>,
    onSelect: (MessageInstructionGroup, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeIndex = group.controls.indexOfFirst { it.data.id in activeInstructionIds }
        .takeIf { it >= 0 }
        ?: group.selectedByDefault
    val activeControl = group.controls[activeIndex]
    val nextIndex = (activeIndex + 1) % group.controls.size

    CompactButton(
        onClick = { onSelect(group, nextIndex) },
        modifier = modifier,
        tooltip = "${group.title}: ${activeControl.data.title}\n${activeControl.data.description}",
    ) {
        Text(
            text = activeControl.shortLabel,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
