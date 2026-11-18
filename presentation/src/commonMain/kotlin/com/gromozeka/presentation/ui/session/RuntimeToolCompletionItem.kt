package com.gromozeka.presentation.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.presentation.ui.LocalTranslation

@Composable
internal fun RuntimeToolCompletionItem(
    activity: ChatActivity.ToolCompletion,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    loadArtifactContent: suspend (Artifact.Id) -> ByteArray,
) {
    val notification = activity.notification
    val translation = LocalTranslation.current
    val title = when {
        notification.level != ContentItem.RuntimeNotification.Level.INFO && notification.content.isNotBlank() ->
            notification.content
        activity.toolName != null -> toolDisplayName(activity.toolName, translation.runtime)
        else -> translation.text("chat.service.runtime")
    }

    Column {
        ActivityHeader(
            kind = activity.kind,
            title = title,
            state = activity.state,
            canExpand = activity.canExpand,
            isExpanded = isExpanded,
            onToggleExpanded = onToggleExpanded,
            modifier = modifier,
        )
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ToolResultContent(notification.attachments, loadArtifactContent)
                    if (notification.content.isNotBlank()) {
                        Text(
                            notification.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    notification.referenceId?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    notification.toolUseId?.let { Text(it.value, style = MaterialTheme.typography.labelSmall) }
                    if (notification.details.isNotEmpty()) {
                        ToolResultContent(
                            listOf(ContentItem.ToolResult.Data.Text(notification.details.toString())),
                            loadArtifactContent,
                        )
                    }
                }
            }
        }
    }
}
