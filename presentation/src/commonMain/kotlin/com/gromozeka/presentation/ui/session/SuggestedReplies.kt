package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
                    ).takeIf { it.values.isNotEmpty() }

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
    modifier: Modifier = Modifier,
) {
    var consumed by remember(options?.sourceMessageId) { mutableStateOf(false) }
    if (options == null || consumed) return

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
                    consumed = true
                },
                label = {
                    Text(
                        text = suggestion,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.testTag(UiTestTag.SuggestedReply(index).value),
            )
        }
    }
}
