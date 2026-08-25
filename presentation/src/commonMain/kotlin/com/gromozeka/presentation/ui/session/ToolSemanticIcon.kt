package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import org.jetbrains.compose.resources.DrawableResource

internal data class ToolIconSpec(
    val domain: DrawableResource,
    val action: DrawableResource? = null,
    val modifier: DrawableResource? = null,
)

@Composable
internal fun ToolSemanticIcon(
    toolName: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    invocationCount: Int = 1,
) {
    val spec = toolIconSpec(toolName)
    Box(modifier = modifier.size(32.dp)) {
        Icon(
            imageVector = spec.domain,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp).align(Alignment.Center),
        )
        spec.modifier?.let { icon ->
            ToolIconBadge(
                icon = icon,
                alignment = Alignment.TopEnd,
                tone = ToolIconBadgeTone.Container,
            )
        }
        spec.action?.let { icon ->
            ToolIconBadge(
                icon = icon,
                alignment = Alignment.BottomEnd,
                tone = ToolIconBadgeTone.Inverse,
            )
        }
        if (invocationCount > 1) {
            ToolInvocationCountBadge(invocationCount)
        }
    }
}

private enum class ToolIconBadgeTone {
    Container,
    Inverse,
}

@Composable
private fun BoxScope.ToolIconBadge(
    icon: DrawableResource,
    alignment: Alignment,
    tone: ToolIconBadgeTone,
) {
    val colorScheme = MaterialTheme.colorScheme
    val (containerColor, contentColor) = when (tone) {
        ToolIconBadgeTone.Container -> colorScheme.surfaceContainerHighest to colorScheme.onSurface
        ToolIconBadgeTone.Inverse -> colorScheme.inverseSurface to colorScheme.inverseOnSurface
    }
    Surface(
        modifier = Modifier.size(15.dp).align(alignment),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(9.dp),
                tint = contentColor,
            )
        }
    }
}

@Composable
private fun BoxScope.ToolInvocationCountBadge(count: Int) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .height(15.dp)
            .defaultMinSize(minWidth = 15.dp)
            .align(Alignment.TopStart),
        shape = CircleShape,
        color = colorScheme.surface,
        contentColor = colorScheme.onSurface,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                fontSize = 8.sp,
                lineHeight = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

internal fun toolIconSpec(toolName: String): ToolIconSpec {
    val actionName = normalizedToolActionName(toolName)

    val domain = when {
        actionName.contains("skill") -> Icons.Default.Extension
        actionName.containsAnyFragment("read_file", "write_file", "edit_file", "file_") -> Icons.Default.Description
        actionName.containsAnyFragment("command", "shell", "terminal") -> Icons.Default.Terminal
        actionName.containsAnyFragment("web", "brave", "jina", "url", "browser") -> Icons.Default.Public
        actionName.containsAnyFragment("lsp", "ast", "code") -> Icons.Default.Code
        actionName.containsAnyFragment("agent", "tab") -> Icons.Default.SmartToy
        actionName.containsAnyFragment("computer", "screenshot") -> Icons.Default.DesktopWindows
        actionName.contains("memory") -> Icons.Default.Psychology
        actionName.contains("mcp") -> Icons.Default.Hub
        actionName.contains("project") -> Icons.Default.AccountTree
        actionName.contains("workspace") -> Icons.Default.Folder
        actionName.contains("worker") -> Icons.Default.DesktopWindows
        actionName.contains("prompt") -> Icons.Default.Subject
        actionName.containsAnyFragment("message", "conversation") -> Icons.Default.ChatBubbleOutline
        actionName.containsAnyFragment("user", "security") -> Icons.Default.Person
        actionName.containsAnyFragment("ai_", "runtime_assignment") -> Icons.Default.Settings
        else -> Icons.Default.Build
    }

    val action = when {
        actionName.contains("read_file") -> Icons.Default.Visibility
        actionName.contains("write_file") -> Icons.Default.Add
        actionName.contains("edit_file") -> Icons.Default.Edit
        actionName.containsAnyFragment("capture_screenshot", "take_screenshot") -> Icons.Default.CameraAlt
        actionName.containsAnyFragment("computer_observe", "observe_screen") -> Icons.Default.Visibility
        actionName.containsAnyFragment("computer_act", "computer_use") -> Icons.Default.TouchApp
        actionName.containsAnyFragment("execute_command", "run_command", "exec_command") -> Icons.Default.ArrowForward
        actionName.contains("local_search") -> Icons.Default.LocationOn
        actionName.containsAnyFragment("search", "find") -> Icons.Default.Search
        actionName.containsAnyFragment("read_url", "web_fetch", "fetch_url") -> Icons.Default.Download
        actionName.contains("materialize") -> Icons.Default.Inventory2
        actionName.contains("activate") -> Icons.Default.Bolt
        actionName.contains("import") -> Icons.Default.ArrowDownward
        actionName.contains("export") -> Icons.Default.ArrowUpward
        actionName.contains("duplicate") -> Icons.Default.ContentCopy
        actionName.containsAnyFragment("tell_agent", "send_input") -> Icons.Default.Send
        actionName.contains("switch_tab") -> Icons.Default.MergeType
        actionName.containsAnyFragment("list", "directory", "namespaces") -> Icons.Default.FormatListBulleted
        actionName.containsAnyFragment("get", "read", "observe") -> Icons.Default.Visibility
        actionName.containsAnyFragment("create", "add", "grant", "remember", "upsert", "attach") -> Icons.Default.Add
        actionName.containsAnyFragment("update", "edit", "set", "declare") -> Icons.Default.Edit
        actionName.containsAnyFragment("delete", "forget", "revoke", "remove") -> Icons.Default.Delete
        actionName.contains("cancel") -> Icons.Default.Close
        actionName.containsAnyFragment("refresh", "rebuild", "restart") -> Icons.Default.Refresh
        actionName.containsAnyFragment("monitor", "status") -> Icons.Default.Schedule
        actionName.containsAnyFragment("help", "environment") -> Icons.Default.Help
        else -> null
    }

    val modifier = when {
        actionName.contains("directory") -> Icons.Default.Folder
        actionName.contains("inline") -> Icons.Default.Code
        else -> null
    }

    return ToolIconSpec(
        domain = domain,
        action = action,
        modifier = modifier,
    )
}
