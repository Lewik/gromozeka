package com.gromozeka.presentation.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode

internal fun interface MarkdownParserBoundary {
    fun parse(content: String): State
}

internal fun interface CodeHighlightBoundary {
    fun highlight(code: String, language: String?, darkTheme: Boolean): List<CodeHighlight>
}

private val DefaultMarkdownParser = MarkdownParserBoundary { parseMarkdown(it) }
private val DefaultCodeHighlighter = CodeHighlightBoundary { code, language, darkTheme ->
    // Builders are mutable: never share one between concurrent jobs or successive languages.
    Highlights.Builder()
        .code(code)
        .language(language?.let(SyntaxLanguage::getByName) ?: SyntaxLanguage.DEFAULT)
        .theme(SyntaxThemes.default(darkMode = darkTheme))
        .build()
        .getHighlights()
}
internal val LocalMarkdownParserBoundary = staticCompositionLocalOf { DefaultMarkdownParser }
internal val LocalCodeHighlightBoundary = staticCompositionLocalOf { DefaultCodeHighlighter }
private val markdownLog = KLoggers.logger("MarkdownRendering")

/** Keep the stack, but not arbitrary parser/highlighter exception messages containing source text. */
internal fun markdownFailureDiagnostic(stage: String, length: Int, error: Exception): String {
    var stack = error.stackTraceToString()
    val pending = ArrayDeque<Throwable>()
    pending.addLast(error)
    val seen = mutableSetOf<Throwable>()
    while (pending.isNotEmpty()) {
        val cause = pending.removeLast()
        if (!seen.add(cause)) continue
        stack = stack.replace(cause.toString(), "${cause::class.simpleName ?: "Throwable"}: <message omitted>")
        cause.cause?.let(pending::addLast)
        cause.suppressedExceptions.forEach(pending::addLast)
    }
    return "Markdown $stage failed; characters=$length; using plain text.\n$stack"
}

private fun reportMarkdownFailure(stage: String, length: Int, error: Exception) {
    markdownLog.warn(markdownFailureDiagnostic(stage, length, error))
}

internal fun validateMarkdownNode(node: ASTNode, contentLength: Int) {
    val pending = ArrayDeque<ASTNode>()
    pending.addLast(node)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        require(current.startOffset >= 0 && current.endOffset >= current.startOffset && current.endOffset <= contentLength) {
            "Invalid Markdown node range [${current.startOffset}, ${current.endOffset}) for length $contentLength"
        }
        current.children.forEach(pending::addLast)
    }
}

internal fun parseMarkdownForPresentation(
    content: String,
    parser: MarkdownParserBoundary = DefaultMarkdownParser,
    onFailure: (String, Int, Exception) -> Unit = ::reportMarkdownFailure,
): State = try {
    parser.parse(content).also { state ->
        // The upstream parser catches Throwable. Restore cancellation/fatal-error semantics here.
        if (state is State.Error) throw state.result
        if (state is State.Success) validateMarkdownNode(state.node, state.content.length)
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    onFailure("parse", content.length, error)
    State.Error(error)
}

@Composable
internal fun rememberGromozekaMarkdownState(content: String, retainState: Boolean = false): State {
    val parser = LocalMarkdownParserBoundary.current
    if (LocalInspectionMode.current) {
        return remember(content, parser) { parseMarkdownForPresentation(content, parser) }
    }
    var state by remember(parser) { mutableStateOf<State>(State.Loading()) }
    val currentContent by rememberUpdatedState(content)
    // Conflate rather than cancelling every parse when incoming text changes rapidly.
    LaunchedEffect(parser, retainState) {
        snapshotFlow { currentContent }.conflate().collect { source ->
            if (!retainState) state = State.Loading()
            state = withContext(Dispatchers.Default) { parseMarkdownForPresentation(source, parser) }
        }
    }
    return state
}

internal fun highlightCodeForPresentation(
    code: String,
    language: String?,
    darkTheme: Boolean,
    highlighter: CodeHighlightBoundary = DefaultCodeHighlighter,
    onFailure: (String, Int, Exception) -> Unit = ::reportMarkdownFailure,
): AnnotatedString = try {
    val highlights = highlighter.highlight(code, language, darkTheme)
    // Validate the whole result before exposing any spans to Compose or Skia. Do not clamp bad data.
    highlights.forEach {
        require(it.location.start >= 0 && it.location.end >= it.location.start && it.location.end <= code.length) {
            "Invalid highlight range [${it.location.start}, ${it.location.end}) for length ${code.length}"
        }
    }
    buildAnnotatedString {
        append(code)
        highlights.forEach {
            val style = when (it) {
                is ColorHighlight -> SpanStyle(color = Color(it.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(style, it.location.start, it.location.end)
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    onFailure("highlight", code.length, error)
    AnnotatedString(code)
}

@Composable
internal fun GromozekaHighlightedCode(code: String, language: String?, style: TextStyle) {
    val highlighter = LocalCodeHighlightBoundary.current
    val darkTheme = isSystemInDarkTheme()
    val immediate = LocalInspectionMode.current
    var highlighted by remember(code, language, darkTheme, highlighter, immediate) {
        mutableStateOf(if (immediate) highlightCodeForPresentation(code, language, darkTheme, highlighter) else AnnotatedString(code))
    }
    if (!immediate) {
        LaunchedEffect(code, language, darkTheme, highlighter) {
            highlighted = withContext(Dispatchers.Default) {
                highlightCodeForPresentation(code, language, darkTheme, highlighter)
            }
        }
    }
    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        language = language,
        code = code,
    ) {
        MarkdownBasicText(
            text = highlighted,
            style = style,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(LocalMarkdownPadding.current.codeBlock),
        )
    }
}
