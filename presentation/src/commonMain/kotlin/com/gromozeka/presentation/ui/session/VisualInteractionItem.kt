package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val interactionJson = Json { prettyPrint = true }

/** A user action, not an invented chat utterance. Expanding never submits or replays it. */
@Composable
internal fun VisualInteractionItem(
    content: Conversation.Message.ContentItem.VisualInteraction,
    onManualContentResize: () -> Unit = {},
) {
    val translation = LocalTranslation.current
    var expanded by remember(content.eventId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("visual-interaction-${content.eventId}"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            DisableSelection {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { onManualContentResize(); expanded = !expanded }
                        .testTag("visual-interaction-toggle-${content.eventId}")
                        .padding(GromozekaTheme.spacing.rowGap),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(GromozekaTheme.spacing.rowGap),
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null,
                        modifier = Modifier.size(GromozekaTheme.controls.smallIconSize))
                    Column(Modifier.weight(1f)) {
                        Text(content.caption(), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(translation.text("visuals.interaction") + " · " + translation.text("visuals.interactionData"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) translation.text("runtime.collapseDescription") else translation.text("runtime.expandDescription"),
                        modifier = Modifier.size(GromozekaTheme.controls.smallIconSize))
                }
            }
            if (expanded) {
                val details = remember(content) { interactionJson.encodeToString(JsonObject.serializer(), content.eventJson()) }
                SelectionContainer {
                    Text(details, modifier = Modifier.fillMaxWidth().padding(GromozekaTheme.spacing.rowGap)
                        .testTag("visual-interaction-data-${content.eventId}"),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
