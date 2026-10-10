package com.gromozeka.presentation.ui

import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.PhraseLocation
import kotlinx.coroutines.CancellationException
import org.intellij.markdown.ast.ASTNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkdownRenderingSafetyTest {
    @Test
    fun invalidHighlightRangesFallBackWithoutClampingOrChangingSource() {
        val code = "val answer = 42 // private source"
        for (range in listOf(PhraseLocation(-1, 2), PhraseLocation(3, 2),
            PhraseLocation(0, code.length + 1), PhraseLocation(Int.MAX_VALUE, Int.MAX_VALUE))) {
            var failures = 0
            val result = highlightCodeForPresentation(code, "kotlin", false,
                highlighter = CodeHighlightBoundary { _, _, _ -> listOf(
                    ColorHighlight(PhraseLocation(0, 3), 0xFF0000), BoldHighlight(range),
                ) },
                onFailure = { stage, length, error ->
                    assertEquals("highlight", stage)
                    assertEquals(code.length, length)
                    assertIs<IllegalArgumentException>(error)
                    assertFalse(error.message.orEmpty().contains(code))
                    failures++
                },
            )
            assertEquals(code, result.text)
            assertTrue(result.spanStyles.isEmpty())
            assertEquals(1, failures)
        }
    }

    @Test
    fun validColorsAndEmphasisArePreserved() {
        val code = "val result = 42"
        val result = highlightCodeForPresentation(code, "kotlin", true,
            highlighter = CodeHighlightBoundary { source, language, dark ->
                assertEquals(code, source)
                assertEquals("kotlin", language)
                assertTrue(dark)
                listOf(ColorHighlight(PhraseLocation(0, 3), 0x0088FF), BoldHighlight(PhraseLocation(4, 10)))
            }, onFailure = { _, _, _ -> error("Valid spans must not fail") },
        )
        assertEquals(code, result.text)
        assertEquals(listOf(0 to 3, 4 to 10), result.spanStyles.map { it.start to it.end })
        assertEquals(FontWeight.Bold, result.spanStyles.last().item.fontWeight)
    }

    @Test
    fun highlighterExceptionsFallBackButCancellationAndErrorsPropagate() {
        val failure = IllegalStateException("synthetic provider failure")
        var observed: Exception? = null
        val result = highlightCodeForPresentation("original", null, false,
            highlighter = CodeHighlightBoundary { _, _, _ -> throw failure },
            onFailure = { _, _, error -> observed = error },
        )
        assertEquals("original", result.text)
        assertTrue(result.spanStyles.isEmpty())
        assertSame(failure, observed)
        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            highlightCodeForPresentation("original", null, false,
                highlighter = CodeHighlightBoundary { _, _, _ -> throw cancelled },
                onFailure = { _, _, _ -> error("Cancellation is not a rendering failure") })
        })
        val fatal = AssertionError("fatal synthetic failure")
        assertSame(fatal, assertFailsWith<AssertionError> {
            highlightCodeForPresentation("original", null, false,
                highlighter = CodeHighlightBoundary { _, _, _ -> throw fatal },
                onFailure = { _, _, _ -> error("Errors must propagate") })
        })
    }

    @Test
    fun parserExceptionsAndReportedErrorsUseTheSameBoundary() {
        val error = IllegalArgumentException("bad synthetic AST")
        for (parser in listOf(MarkdownParserBoundary { throw error }, MarkdownParserBoundary { State.Error(error) })) {
            var failures = 0
            val result = parseMarkdownForPresentation("unchanged **source**", parser) { stage, length, failure ->
                assertEquals("parse", stage)
                assertEquals(20, length)
                assertSame(error, failure)
                failures++
            }
            assertSame(error, assertIs<State.Error>(result).result)
            assertEquals(1, failures)
        }
    }

    @Test
    fun upstreamReportedCancellationAndFatalErrorsAreNotSwallowed() {
        val cancelled = CancellationException("stop parsing")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            parseMarkdownForPresentation("source", MarkdownParserBoundary { State.Error(cancelled) }) { _, _, _ ->
                error("Cancellation must propagate")
            }
        })
        val fatal = AssertionError("fatal parser failure")
        assertSame(fatal, assertFailsWith<AssertionError> {
            parseMarkdownForPresentation("source", MarkdownParserBoundary { State.Error(fatal) }) { _, _, _ ->
                error("Errors must propagate")
            }
        })
    }

    @Test
    fun invalidAstOffsetsFailBeforeAnyComposableSlicesTheSource() {
        val source = "A paragraph"
        val valid = assertIs<State.Success>(parseMarkdown(source))
        for (range in listOf(-1 to 3, 4 to 3, 0 to source.length + 1)) {
            val invalid = object : ASTNode by valid.node {
                override val startOffset = range.first
                override val endOffset = range.second
            }
            val result = parseMarkdownForPresentation(source, MarkdownParserBoundary { valid.copy(node = invalid) }) { _, _, _ -> }
            assertIs<State.Error>(result)
        }
        assertSame(valid, parseMarkdownForPresentation(source, MarkdownParserBoundary { valid }) { _, _, _ ->
            error("Valid AST must remain usable")
        })
    }

    @Test
    fun actualHighlighterPreservesUnicodeUnterminatedAndUnknownLanguageInputs() {
        val sources = listOf("", "val value = 42", "\"unfinished", "/* unfinished", "# comment\r\n", "😀 é עברית\n\t\\\"", "```\n")
        for (source in sources) for (language in listOf(null, "kotlin", "python", "javascript", "unknown-language")) {
            val result = highlightCodeForPresentation(source, language, false)
            assertEquals(source, result.text)
            assertTrue(result.spanStyles.all { it.start >= 0 && it.end >= it.start && it.end <= source.length })
        }
        assertTrue(highlightCodeForPresentation("val value = 42", "kotlin", false).spanStyles.isNotEmpty())
    }

    @Test
    fun diagnosticsKeepStackLocationsButRedactExceptionSourceExcerpts() {
        val error = IllegalStateException("private-source-outer", IllegalArgumentException("private-source-inner"))
        error.addSuppressed(IllegalArgumentException("private-source-suppressed"))
        val diagnostic = markdownFailureDiagnostic("highlight", 123, error)
        assertTrue(diagnostic.contains("characters=123"))
        assertTrue(diagnostic.contains("IllegalStateException"))
        assertTrue(diagnostic.contains("IllegalArgumentException"))
        assertTrue(diagnostic.contains("MarkdownRenderingSafetyTest.kt:"))
        assertFalse(diagnostic.contains("private-source-outer"))
        assertFalse(diagnostic.contains("private-source-inner"))
        assertFalse(diagnostic.contains("private-source-suppressed"))
    }
}
