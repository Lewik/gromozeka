package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.visual.*
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.OptionalTooltip
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.CompactButtonDefaults
import com.gromozeka.presentation.ui.CompactTextField
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun VisualContent(
    visual: Visual, draft: VisualFormDraft, service: VisualService, modifier: Modifier = Modifier,
    highlightedIds: Set<String> = emptySet(), onClearHighlights: () -> Unit = {},
) {
    val translation = LocalTranslation.current
    val scope = rememberCoroutineScope()
    val compilation = remember(visual.documentRevision, visual.document) { runCatching { VisualDocumentCompiler.compile(visual.document) } }
    val document = compilation.getOrNull()
    val state = draft.state(visual)
    val rendering = remember(document, state) { document?.let { runCatching { VisualDocumentCompiler.render(it, state) } } }
    var lastGood by remember(visual.id) { mutableStateOf<List<VisualRenderNode>>(emptyList()) }
    val nodes = rendering?.getOrNull() ?: lastGood
    SideEffect { rendering?.getOrNull()?.let { lastGood = it } }
    val failure = compilation.exceptionOrNull()?.message ?: rendering?.exceptionOrNull()?.message
    val requesters = remember(visual.id) { mutableMapOf<String, FocusRequester>() }
    val latestVisual by rememberUpdatedState(visual)
    val uriHandler = LocalUriHandler.current
    val onAction: (String) -> Unit = { button ->
        if (!draft.sending && latestVisual.status == VisualStatus.ACTIVE && document != null && failure == null) {
            try {
                val submitted = VisualDocumentCompiler.submittedState(document, draft.state(latestVisual))
                val eventId = uuid7()
                val action = VisualAction(eventId, latestVisual.id, latestVisual.documentRevision, button, submitted)
                draft.submitted(eventId, submitted)
                scope.launch {
                    try {
                        val result = service.act(latestVisual.conversationId, action)
                        if (!result.accepted) draft.error = result.error ?: translation.text("visuals.actionFailed")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { draft.error = error.message ?: translation.text("visuals.actionFailed") }
                    finally { draft.sending = false }
                }
            } catch (error: Exception) { draft.error = error.message ?: translation.text("visuals.actionFailed") }
        }
    }
    val halfSpacing = 4.dp
    val context = RenderContext(
        state = state,
        halfSpacing = halfSpacing,
        isFieldDirty = draft::isFieldDirty,
        highlightedIds = highlightedIds,
        onClearHighlights = onClearHighlights,
        palette = document?.palette.orEmpty().mapValues { (_, hex) -> Color(0xFF000000L or hex.removePrefix("#").toLong(16)) },
        interactive = visual.status == VisualStatus.ACTIVE && !draft.closing,
        sending = draft.sending || failure != null,
        focusRequesters = requesters,
        onEdit = { path, value, numberEditor ->
            try { draft.edit(latestVisual, path, value, numberEditor) }
            catch (error: Exception) { draft.error = error.message ?: translation.text("visuals.actionFailed") }
        },
        onAction = onAction,
        onLink = { url ->
            try { uriHandler.openUri(url) }
            catch (error: Exception) { draft.error = error.message ?: translation.text("visuals.actionFailed") }
        },
    )
    Column(modifier.fillMaxSize().testTag("visual-content-${visual.id}")) {
        Column(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().verticalScroll(rememberScrollState())
                .padding(horizontal = halfSpacing + 4.dp, vertical = halfSpacing).testTag("visual-body-${visual.id}"),
        ) {
            nodes.forEach { node -> key(node.key) { VisualNodeContent(node, context) } }
        }
        // Host chrome owns its own inset; document layout has only the half-spacing above.
        Column(Modifier.fillMaxWidth().padding(horizontal = GromozekaTheme.spacing.contentPadding)
            .padding(bottom = GromozekaTheme.spacing.contentPadding)) {
            HorizontalDivider(Modifier.padding(top = GromozekaTheme.spacing.rowGap))
            val diagnostic = failure ?: draft.error
                ?: visual.diagnostics.lastOrNull { it.code != "handler-stopped" }?.message
                ?: visual.diagnostics.lastOrNull()?.message
            val status = when {
                draft.sending -> translation.text("visuals.sending")
                visual.status == VisualStatus.STARTING -> translation.text("visuals.starting")
                visual.status == VisualStatus.STOPPED -> translation.text("visuals.stopped")
                visual.handler == null -> translation.text("visuals.llm")
                else -> requireNotNull(visual.handler).worker.workerId.value
            }
            Text(status, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = GromozekaTheme.spacing.controlGap).testTag("visual-status"))
            if (diagnostic != null) {
                OptionalTooltip(diagnostic) {
                    Text("${translation.text("visuals.errors")}: $diagnostic",
                        modifier = Modifier.fillMaxWidth().padding(vertical = GromozekaTheme.spacing.controlGap).testTag("visual-error"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private data class RenderContext(
    val state: JsonObject,
    val halfSpacing: Dp,
    val isFieldDirty: (String) -> Boolean,
    val highlightedIds: Set<String>,
    val onClearHighlights: () -> Unit,
    val palette: Map<String, Color>,
    val interactive: Boolean,
    val sending: Boolean,
    val focusRequesters: MutableMap<String, FocusRequester>,
    val onEdit: (String, JsonElement, Boolean) -> Unit,
    val onAction: (String) -> Unit,
    val onLink: (String) -> Unit,
) {
    fun edit(node: VisualRenderNode, value: JsonElement) = onEdit(
        node.string("name")!!, value, node.tag == "input" && node.string("type") in setOf("number", "range"),
    )
    fun color(node: VisualRenderNode): Color = node.string("color")?.let(palette::get) ?: Color.Unspecified
    fun formValue(node: VisualRenderNode): JsonElement = VisualPath.get(requireNotNull(node.string("name")), state)
    fun requester(node: VisualRenderNode): FocusRequester = focusRequesters.getOrPut(node.string("id") ?: node.string("name") ?: node.key) { FocusRequester() }
}

@Composable
private fun VisualNodeContent(node: VisualRenderNode, context: RenderContext, modifier: Modifier = Modifier) {
    // Resolve once and pass the same shape to the native control and its attention halo.
    val shape = when (node.tag) {
        "button", "textarea", "select" -> MaterialTheme.shapes.small
        "input" -> if (node.string("type") in setOf("checkbox", "range")) RectangleShape else MaterialTheme.shapes.small
        else -> RectangleShape
    }
    // Containers only arrange children. Atomic rendered content owns one exterior h-inset.
    // Inline descendants are flattened into their enclosing text/button, not laid out again.
    val spaced = if (node.tag in setOf("div", "ul", "ol", "li")) modifier else modifier.padding(context.halfSpacing)
    val id = node.string("id")
    if (id == null) VisualNodeBody(node, context, shape, spaced)
    else VisualHighlightContainer(id in context.highlightedIds, id, context.onClearHighlights, spaced, shape) {
        VisualNodeBody(node, context, shape)
    }
}

@Composable
private fun VisualNodeBody(node: VisualRenderNode, context: RenderContext, shape: Shape, modifier: Modifier = Modifier) {
    val tagged = modifier.testTag("visual-element-${node.string("id") ?: node.key}")
    OptionalTooltip(node.string("title")) {
        when (node.tag) {
            "div" -> VisualDiv(node, context, tagged)
            "h1", "h2", "h3", "p", "span", "small", "pre", "label", "#text", "a" -> {
                val style = when (node.tag) {
                    "h1" -> MaterialTheme.typography.headlineLarge
                    "h2" -> MaterialTheme.typography.headlineMedium
                    "h3" -> MaterialTheme.typography.titleLarge
                    "small" -> MaterialTheme.typography.bodySmall
                    "pre" -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    else -> MaterialTheme.typography.bodyMedium
                }
                val text = inlineText(node, context, preserveWhitespace = node.tag == "pre")
                val textModifier = if (node.tag == "label" && node.string("for") != null) tagged.clickable {
                    context.focusRequesters[node.string("for")]?.let { runCatching { it.requestFocus() } }
                } else tagged
                VisualAnnotatedText(text, context.onClearHighlights, modifier = textModifier, style = style, color = context.color(node))
            }
            "br" -> Spacer(tagged.height(0.dp))
            "hr" -> HorizontalDivider(tagged)
            "button" -> CompactButton(
                onClick = { context.onAction(node.string("id")!!) },
                enabled = context.interactive && !context.sending && !node.bool("disabled"),
                modifier = tagged,
                shape = shape,
                colors = CompactButtonDefaults.tonalColors(),
                elevation = null,
            ) { VisualAnnotatedText(inlineText(node, context), context.onClearHighlights, color = context.color(node)) }
            "input", "textarea", "select" -> VisualDirtyBadge(
                dirty = context.isFieldDirty(requireNotNull(node.string("name"))),
                badgeTag = "visual-field-dirty-${node.string("id") ?: node.key}",
                modifier = modifier.testTag("visual-field-container-${node.string("id") ?: node.key}"),
            ) {
                VisualFieldContent(node, context, Modifier.testTag("visual-element-${node.string("id") ?: node.key}"), shape)
            }
            "ul", "ol" -> Column(tagged.fillMaxWidth()) {
                val start = node.number("start")?.toInt() ?: 1
                node.children.forEachIndexed { index, item -> key(item.key) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(if (node.tag == "ol") "${start + index}." else "•", color = context.color(node), modifier = Modifier.padding(context.halfSpacing))
                        Column(Modifier.weight(1f)) { VisualNodeContent(item, context) }
                    }
                } }
            }
            "li" -> VisualMixedChildren(node.children, context, tagged)
            "progress" -> {
                val value = node.number("value")
                val color = context.color(node).takeOrElse { MaterialTheme.colorScheme.primary }
                if (value == null) LinearProgressIndicator(tagged.fillMaxWidth(), color = color)
                else LinearProgressIndicator(progress = { (value / (node.number("max") ?: 1.0)).toFloat().coerceIn(0f, 1f) }, modifier = tagged.fillMaxWidth(), color = color)
            }
            // option is rendered only by its owning select; control directives are already expanded.
            "option" -> Unit
        }
    }
}

@Composable
private fun VisualFieldContent(node: VisualRenderNode, context: RenderContext, tagged: Modifier, shape: Shape) {
    when (node.tag) {
        "input" -> when (node.string("type") ?: "text") {
            "checkbox" -> Checkbox(
                checked = (context.formValue(node) as? JsonPrimitive)?.booleanOrNull == true,
                onCheckedChange = { context.edit(node, JsonPrimitive(it)) },
                enabled = context.interactive && !node.bool("disabled"),
                modifier = tagged.focusRequester(context.requester(node)).semantics { contentDescription = node.string("title") ?: node.string("name").orEmpty() },
            )
            "range" -> {
                val lower = node.number("min") ?: 0.0
                val upper = node.number("max") ?: 100.0
                val min = lower.toFloat()
                val max = upper.toFloat()
                val value = (context.formValue(node) as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: min
                val step = node.number("step")
                Slider(value = value.coerceIn(min, max), onValueChange = { next ->
                    val quantized = if (step != null) lower + kotlin.math.round((next.toDouble() - lower) / step) * step else next.toDouble()
                    context.edit(node, JsonPrimitive(quantized.coerceIn(lower, upper)))
                }, valueRange = min..max, enabled = context.interactive && !node.bool("disabled"),
                    modifier = tagged.fillMaxWidth().semantics { contentDescription = node.string("title") ?: node.string("name").orEmpty() })
            }
            else -> VisualTextField(node, context, tagged, shape, multiline = false)
        }
        "textarea" -> VisualTextField(node, context, tagged, shape, multiline = true)
        "select" -> VisualSelect(node, context, tagged, shape)
    }
}

@Composable
private fun VisualDiv(node: VisualRenderNode, context: RenderContext, modifier: Modifier) {
    var decorated = modifier.fillMaxWidth()
    node.string("background")?.let { context.palette[it] }?.let { decorated = decorated.background(it) }
    if (node.bool("border")) decorated = decorated.border(1.dp, MaterialTheme.colorScheme.outlineVariant)
    when (node.string("layout") ?: "column") {
        "row" -> Row(decorated, verticalAlignment = Alignment.CenterVertically) {
            node.children.forEach { child -> key(child.key) {
                val sizing = if (child.tag in setOf("input", "textarea", "select", "div") && child.string("type") != "checkbox") Modifier.weight(1f) else Modifier
                VisualNodeContent(child, context, sizing)
            } }
        }
        "grid" -> {
            val tracks = VisualDocumentCompiler.gridColumns(node.string("columns")!!)
            Grid(config = {
                tracks.forEach { track ->
                    if (track == "auto") column(GridTrackSize.Auto)
                    else column(track.removeSuffix("fr").toFloat().fr)
                }
                repeat((node.children.size + tracks.size - 1) / tracks.size) { row(GridTrackSize.Auto) }
                gap(0.dp)
            }, modifier = decorated) {
                node.children.forEach { child -> key(child.key) { VisualNodeContent(child, context) } }
            }
        }
        else -> Column(decorated) {
            node.children.forEach { child -> key(child.key) { VisualNodeContent(child, context) } }
        }
    }
}

@Composable
private fun VisualTextField(node: VisualRenderNode, context: RenderContext, modifier: Modifier, shape: Shape, multiline: Boolean) {
    val value = VisualBinding.text(context.formValue(node))
    var editor by remember(node.key, node.string("name")) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (editor.text != value) editor = TextFieldValue(value, TextRange(editor.selection.end.coerceAtMost(value.length)))
    }
    val name = node.string("name")!!
    CompactTextField(
        value = editor,
        onValueChange = { next ->
            if (next.text.length <= (node.number("maxlength")?.toInt() ?: VisualLimits.STATE_BYTES)) {
                editor = next
                context.edit(node, JsonPrimitive(next.text))
            }
        },
        modifier = modifier.fillMaxWidth().focusRequester(context.requester(node))
            .testTag("visual-input-$name").semantics { contentDescription = node.string("title") ?: name },
        shape = shape,
        singleLine = !multiline,
        minLines = if (multiline) (node.number("rows")?.toInt() ?: 3) else 1,
        maxLines = if (multiline) (node.number("rows")?.toInt() ?: 6) else 1,
        readOnly = node.bool("readonly") || !context.interactive,
        enabled = !node.bool("disabled"),
        placeholder = node.string("placeholder")?.let { placeholder -> { Text(placeholder) } },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = context.color(node)),
        keyboardOptions = KeyboardOptions(keyboardType = if (node.string("type") == "number") KeyboardType.Decimal else KeyboardType.Text),
    )
}

@Composable
private fun VisualSelect(node: VisualRenderNode, context: RenderContext, modifier: Modifier, shape: Shape) {
    var expanded by remember(node.key) { mutableStateOf(false) }
    val value = context.formValue(node)
    val options = node.children.filter { it.tag == "option" }
    val selected = options.firstOrNull { it.attributes["value"] == value }
    val name = node.string("name")!!
    Box(modifier.fillMaxWidth()) {
        CompactButton(onClick = { expanded = true }, enabled = context.interactive && !node.bool("disabled"),
            shape = shape, colors = CompactButtonDefaults.tonalColors(), elevation = null,
            modifier = Modifier.fillMaxWidth().focusRequester(context.requester(node))
                .testTag("visual-select-$name").semantics { contentDescription = node.string("title") ?: name }) {
            VisualAnnotatedText(selected?.let { inlineText(it, context, highlightRoot = true) } ?: AnnotatedString(VisualBinding.text(value)),
                context.onClearHighlights, color = context.color(node), modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, contentDescription = null,
                modifier = Modifier.padding(start = GromozekaTheme.spacing.rowGap).size(GromozekaTheme.controls.smallIconSize))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> key(option.key) {
                DropdownMenuItem(text = { VisualAnnotatedText(inlineText(option, context, highlightRoot = true), context.onClearHighlights) }, enabled = !option.bool("disabled"), onClick = {
                    context.edit(node, option.attributes.getValue("value")); expanded = false
                })
            } }
        }
    }
}

@Composable
private fun VisualMixedChildren(children: List<VisualRenderNode>, context: RenderContext, modifier: Modifier = Modifier) {
    if (children.all { it.tag in setOf("#text", "span", "small", "a", "br") }) {
        VisualAnnotatedText(inlineText(VisualRenderNode("span", JsonObject(emptyMap()), children, "inline"), context), context.onClearHighlights, modifier = modifier.padding(context.halfSpacing))
    } else Column(modifier) {
        children.forEach { child -> key(child.key) { VisualNodeContent(child, context) } }
    }
}

@Composable
private fun inlineText(node: VisualRenderNode, context: RenderContext, preserveWhitespace: Boolean = false, highlightRoot: Boolean = false): AnnotatedString {
    val small = MaterialTheme.typography.bodySmall.fontSize
    val linkColor = MaterialTheme.colorScheme.primary
    val highlightShadow = androidx.compose.ui.graphics.Shadow(
        color = Color.White.copy(alpha = 0.65f),
        blurRadius = with(LocalDensity.current) { 7.dp.toPx() },
    )
    return buildAnnotatedString {
        fun appendNode(current: VisualRenderNode) {
            if (current.tag == "br") { append('\n'); return }
            val format = current.string("format").orEmpty().split(' ').toSet()
            val highlighted = (highlightRoot || current !== node) && current.string("id")?.let(context.highlightedIds::contains) == true
            if (highlighted) pushStringAnnotation(VISUAL_INLINE_HIGHLIGHT, requireNotNull(current.string("id")))
            val style = SpanStyle(
                shadow = if (highlighted) highlightShadow else null,
                color = context.color(current),
                fontWeight = if ("bold" in format) FontWeight.Bold else null,
                fontStyle = if ("italic" in format) FontStyle.Italic else null,
                fontFamily = if ("mono" in format || current.tag == "pre") FontFamily.Monospace else null,
                fontSize = if (current.tag == "small") small else androidx.compose.ui.unit.TextUnit.Unspecified,
            )
            pushStyle(style)
            val href = if (current.tag == "a") current.string("href") else null
            if (href != null) pushLink(LinkAnnotation.Url(href,
                styles = TextLinkStyles(style = SpanStyle(color = context.color(current).takeOrElse { linkColor })),
                linkInteractionListener = LinkInteractionListener { context.onLink(href) }))
            current.text?.let { append(if (preserveWhitespace) it else it.replace(Regex("\\s+"), " ")) }
            current.children.forEach(::appendNode)
            if (href != null) pop()
            pop()
            if (highlighted) pop()
        }
        appendNode(node)
    }
}
