package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.impl.ListCompositeNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private val HtmlLineBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val HiddenBlockTypes = setOf(
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.WHITE_SPACE,
    MarkdownTokenTypes.LIST_BULLET,
    MarkdownTokenTypes.LIST_NUMBER,
    MarkdownElementTypes.LINK_DEFINITION,
    GFMTokenTypes.CHECK_BOX,
)
private val BlockSpacing = 8.dp

private val GromozekaMarkdownAnnotator = markdownAnnotator(
    config = markdownAnnotatorConfig(eolAsNewLine = true),
) { content, node ->
    if (node.parent?.type == MarkdownElementTypes.CODE_SPAN) {
        append(if (node.type == MarkdownTokenTypes.EOL) " " else content.substring(node.startOffset, node.endOffset))
        true
    } else if (node.type == MarkdownTokenTypes.HTML_TAG &&
        HtmlLineBreak.matches(content.substring(node.startOffset, node.endOffset))
    ) {
        append('\n')
        true
    } else {
        false
    }
}

internal fun ASTNode.visibleMarkdownBlocks(content: String): List<ASTNode> = children.filter {
    it.type !in HiddenBlockTypes && (it.type != MarkdownElementTypes.HTML_BLOCK ||
        HtmlLineBreak.matches(content.substring(it.startOffset, it.endOffset).trim()))
}

@Composable
fun GromozekaMarkdown(content: String, modifier: Modifier = Modifier) {
    val markdownState = rememberMarkdownState(content)
    val state by markdownState.state.collectAsState()
    GromozekaMarkdownContent(state, modifier = modifier)
}

@Composable
fun GromozekaMarkdownNode(state: State.Success, node: ASTNode, modifier: Modifier = Modifier) {
    GromozekaMarkdownContent(state, node, modifier)
}

@Composable
private fun GromozekaMarkdownContent(state: State, node: ASTNode? = null, modifier: Modifier) {
    val bodyStyle = MaterialTheme.typography.bodyMedium
    Markdown(
        state = state,
        modifier = modifier,
        typography = markdownTypography(
            h1 = MaterialTheme.typography.headlineLarge,
            h2 = MaterialTheme.typography.headlineMedium,
            h3 = MaterialTheme.typography.headlineSmall,
            h4 = MaterialTheme.typography.titleLarge,
            h5 = MaterialTheme.typography.titleMedium,
            h6 = MaterialTheme.typography.titleSmall,
            text = bodyStyle,
            paragraph = bodyStyle,
            ordered = bodyStyle,
            bullet = bodyStyle,
            list = bodyStyle,
            textLink = TextLinkStyles(
                style = bodyStyle.copy(
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                ).toSpanStyle(),
            ),
        ),
        padding = markdownPadding(block = BlockSpacing),
        annotator = GromozekaMarkdownAnnotator,
        components = GromozekaMarkdownComponents,
        success = { parsed, components, contentModifier ->
            val blocks = remember(parsed) { parsed.node.visibleMarkdownBlocks(parsed.content) }
            Column(contentModifier) {
                if (node == null) {
                    blocks.forEachIndexed { index, block ->
                        GromozekaMarkdownBlock(parsed.content, block, components, index > 0)
                    }
                } else {
                    GromozekaMarkdownBlock(parsed.content, node, components, blocks.indexOf(node) > 0)
                }
            }
        },
    )
}

@Composable
private fun GromozekaMarkdownBlock(
    content: String,
    node: ASTNode,
    components: MarkdownComponents,
    separated: Boolean,
) {
    if (separated) Spacer(Modifier.height(BlockSpacing))
    MarkdownElement(node, components, content, includeSpacer = false)
}

@Composable
internal fun GromozekaMarkdownCustomBlock(type: IElementType, model: MarkdownComponentModel) {
    if (type == MarkdownElementTypes.HTML_BLOCK) {
        if (HtmlLineBreak.matches(model.content.substring(model.node.startOffset, model.node.endOffset).trim())) {
            val lineHeight = with(LocalDensity.current) { model.typography.text.lineHeight.toDp() }
            Spacer(Modifier.height(lineHeight))
        }
    } else {
        val components = LocalMarkdownComponents.current
        model.node.children.forEach {
            MarkdownElement(it, components, model.content, includeSpacer = false)
        }
    }
}

@Composable
internal fun GromozekaMarkdownBlockQuote(model: MarkdownComponentModel) {
    val color = LocalMarkdownColors.current.text
    val thickness = LocalMarkdownDimens.current.blockQuoteThickness
    val components = LocalMarkdownComponents.current
    val labels = LocalMarkdownA11yLabels.current
    val blocks = remember(model.node, model.content) { model.node.visibleMarkdownBlocks(model.content) }
    Column(
        Modifier.semantics { contentDescription = labels.blockquote }.drawBehind {
            val x = thickness.toPx() / 2
            drawLine(color, Offset(x, 0f), Offset(x, size.height), thickness.toPx())
        }.padding(start = 16.dp),
    ) {
        blocks.forEachIndexed { index, node ->
            GromozekaMarkdownBlock(model.content, node, components, index > 0)
        }
    }
}

@Composable
internal fun GromozekaMarkdownParagraph(model: MarkdownComponentModel) {
    val parent = model.node.parent
    val separate = parent?.type == MarkdownElementTypes.LIST_ITEM &&
        parent.visibleMarkdownBlocks(model.content).indexOf(model.node) > 0
    MarkdownParagraph(
        model.content,
        model.node,
        modifier = Modifier.padding(top = if (separate) BlockSpacing else 0.dp),
        style = model.typography.paragraph,
    )
}

@Composable
internal fun GromozekaMarkdownTable(model: MarkdownComponentModel) {
    MarkdownTable(
        content = model.content,
        node = model.node,
        style = model.typography.table,
        headerBlock = { content, header, width, style ->
            MarkdownTableHeader(content, header, width, style, maxLines = Int.MAX_VALUE)
        },
        rowBlock = { content, row, width, style ->
            MarkdownTableRow(content, row, width, style, maxLines = Int.MAX_VALUE)
        },
    )
}

@Composable
internal fun GromozekaMarkdownList(model: MarkdownComponentModel) {
    val loose = (model.node as ListCompositeNode).loose
    val padding = LocalMarkdownPadding.current
    val listPadding = remember(padding, loose) {
        object : MarkdownPadding by padding {
            override val list = 0.dp
            override val listItemTop = if (loose) 4.dp else 0.dp
            override val listItemBottom = if (loose) 4.dp else 0.dp
        }
    }
    CompositionLocalProvider(LocalMarkdownPadding provides listPadding) {
        if (model.node.type == MarkdownElementTypes.ORDERED_LIST) {
            MarkdownOrderedList(model.content, model.node, model.typography.ordered, model.listDepth)
        } else {
            MarkdownBulletList(model.content, model.node, model.typography.bullet, model.listDepth)
        }
    }
}
