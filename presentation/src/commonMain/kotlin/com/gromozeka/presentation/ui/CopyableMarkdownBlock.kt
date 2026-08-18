package com.gromozeka.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import kotlinx.coroutines.delay

internal data class CopyableMarkdownBlockSpec(
    val label: String?,
    val icon: Icon,
    val language: String?,
) {
    enum class Icon {
        NONE,
        TERMINAL,
        CODE,
        DOCUMENT,
        LINK,
    }
}

internal fun parseCopyableMarkdownBlockInfo(info: String?): CopyableMarkdownBlockSpec? {
    val normalized = info?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val directive = normalized.takeWhile { !it.isWhitespace() }
    if (directive != COPYABLE_MARKDOWN_DIRECTIVE) return null

    val attributes = COPYABLE_MARKDOWN_ATTRIBUTE.findAll(normalized.removePrefix(directive))
        .associate { match -> match.groupValues[1] to match.groupValues[2] }
    val label = attributes["label"]
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.take(COPYABLE_MARKDOWN_LABEL_MAX_LENGTH)
        ?.takeIf(String::isNotEmpty)
    val icon = when (attributes["icon"]?.lowercase()) {
        "terminal" -> CopyableMarkdownBlockSpec.Icon.TERMINAL
        "code" -> CopyableMarkdownBlockSpec.Icon.CODE
        "document" -> CopyableMarkdownBlockSpec.Icon.DOCUMENT
        "link" -> CopyableMarkdownBlockSpec.Icon.LINK
        else -> CopyableMarkdownBlockSpec.Icon.NONE
    }
    val language = attributes["language"]
        ?.takeIf(COPYABLE_MARKDOWN_LANGUAGE::matches)

    return CopyableMarkdownBlockSpec(
        label = label,
        icon = icon,
        language = language,
    )
}

internal val GromozekaMarkdownComponents: MarkdownComponents = markdownComponents(
    codeFence = { model -> GromozekaCodeFence(model) },
    codeBlock = highlightedCodeBlock,
)

@Composable
private fun GromozekaCodeFence(model: MarkdownComponentModel) {
    MarkdownCodeFence(
        content = model.content,
        node = model.node,
        style = model.typography.code,
    ) { code, info, style ->
        val spec = parseCopyableMarkdownBlockInfo(info)
        if (spec == null) {
            MarkdownHighlightedCode(
                code = code,
                language = info,
                style = style,
            )
        } else {
            CopyableMarkdownBlock(
                code = code,
                spec = spec,
                style = style,
            )
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun CopyableMarkdownBlock(
    code: String,
    spec: CopyableMarkdownBlockSpec,
    style: TextStyle,
) {
    val translation = LocalTranslation.current.runtime
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_STATUS_DURATION_MILLIS)
            copied = false
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(UiTestTag.CopyableMarkdownBlock.value),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                spec.icon.imageVector()?.let { imageVector ->
                    Icon(
                        imageVector = imageVector,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = spec.label ?: translation.copyText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                    },
                    modifier = Modifier.testTag(UiTestTag.CopyableMarkdownButton.value),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) translation.copiedText else translation.copyText,
                    )
                }
            }
            MarkdownHighlightedCode(
                code = code,
                language = spec.language,
                style = style,
            )
        }
    }
}

private fun CopyableMarkdownBlockSpec.Icon.imageVector() = when (this) {
    CopyableMarkdownBlockSpec.Icon.NONE -> null
    CopyableMarkdownBlockSpec.Icon.TERMINAL -> Icons.Default.Terminal
    CopyableMarkdownBlockSpec.Icon.CODE -> Icons.Default.Code
    CopyableMarkdownBlockSpec.Icon.DOCUMENT -> Icons.Default.Description
    CopyableMarkdownBlockSpec.Icon.LINK -> Icons.Default.Link
}

private const val COPYABLE_MARKDOWN_DIRECTIVE = "gromozeka-copy"
private const val COPYABLE_MARKDOWN_LABEL_MAX_LENGTH = 80
private const val COPIED_STATUS_DURATION_MILLIS = 1_500L
private val COPYABLE_MARKDOWN_ATTRIBUTE = Regex("""([A-Za-z][A-Za-z0-9_-]*)="([^"\r\n]{0,160})"""")
private val COPYABLE_MARKDOWN_LANGUAGE = Regex("[A-Za-z0-9_+.-]{1,24}")
