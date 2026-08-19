package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.UiTestTag

internal data class SuggestedReplyOptions(
    val sourceMessageId: Conversation.Message.Id,
    val values: List<String>,
)

internal fun latestSuggestedReplies(messages: List<Conversation.Message>): SuggestedReplyOptions? {
    messages.asReversed().forEach { message ->
        message.content.asReversed().forEach { content ->
            when (content) {
                is Conversation.Message.ContentItem.AssistantMessage ->
                    return SuggestedReplyOptions(
                        sourceMessageId = message.id,
                        values = content.structured.suggestedReplies,
                    )

                is Conversation.Message.ContentItem.UserMessage -> return null
                else -> Unit
            }
        }
    }
    return null
}

@Composable
internal fun SuggestedReplyChips(
    options: SuggestedReplyOptions?,
    onSuggestionSelected: (String) -> Unit,
    onRegenerate: (Conversation.Message.Id) -> Unit,
    isRegenerating: Boolean,
    modifier: Modifier = Modifier,
) {
    if (options == null) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag(UiTestTag.SuggestedReplies.value),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.values.forEachIndexed { index, suggestion ->
            SuggestionChip(
                onClick = {
                    onSuggestionSelected(suggestion)
                },
                label = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = suggestion,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .testTag(UiTestTag.SuggestedReply(index).value),
            )
        }
        IconButton(
            onClick = { onRegenerate(options.sourceMessageId) },
            enabled = !isRegenerating,
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag(UiTestTag.SuggestedRepliesRefresh.value),
        ) {
            if (isRegenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Regenerate suggested replies",
                )
            }
        }
    }
}
