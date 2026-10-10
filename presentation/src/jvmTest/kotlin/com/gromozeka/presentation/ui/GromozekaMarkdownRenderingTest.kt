package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import com.mikepenz.markdown.model.parseMarkdown
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.PhraseLocation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.session.MessageItem
import com.gromozeka.presentation.ui.session.rememberMessageListEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class GromozekaMarkdownRenderingTest {
    @Test
    fun preservesSoftAndExplicitLineBreaks() = runDesktopComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                GromozekaTheme {
                    GromozekaMarkdown("First\nSecond<br>Third<BR />Fourth  \nFifth\\\nSixth")
                }
            }
        }
        onNodeWithText("First\nSecond\nThird\nFourth\nFifth\nSixth").assertExists()
    }

    @Test
    fun wholeDocumentSeparatesParagraphs() = verifyParagraphSpacing(segmented = false)

    @Test
    fun segmentedMessageSeparatesParagraphsConsistently() = verifyParagraphSpacing(segmented = true)

    @Test
    fun listItemParagraphsRemainSeparate() = runDesktopComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                GromozekaTheme {
                    GromozekaMarkdown("- First\n\n  Second\n\n- Third")
                }
            }
        }
        val first = onNodeWithText("First").fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText("Second").fetchSemanticsNode().boundsInRoot
        assertTrue(second.top - first.bottom >= 8f)
    }

    @Test
    fun lineBreakHandlingDoesNotRewriteCode() = runDesktopComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                GromozekaTheme {
                    GromozekaMarkdown("Before `<br>` and `a\nb`.\n\n```text\nx<br>y\nz\n```")
                }
            }
        }
        val prose = onNodeWithText("Before", substring = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(prose.contains("<br>"), prose)
        assertTrue(prose.contains("a b"), prose)
        assertTrue(!prose.contains('\n'))
        onNodeWithText("x<br>y\nz").assertExists()
    }

    @Test
    fun referenceDefinitionsDoNotCreateVisibleSegments() = runDesktopComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                GromozekaTheme {
                    GromozekaMarkdown("[example]: https://example.com\n\nFirst\n\n[Second][example]")
                }
            }
        }
        val first = onNodeWithText("First").fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText("Second").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, first.top)
        assertTrue(second.top - first.bottom >= 8f)
    }

    @Test
    fun looseListHasMoreSpaceThanTightList() = runDesktopComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                GromozekaTheme {
                    GromozekaMarkdown("- Tight one\n- Tight two\n\n---\n\n- Loose one\n\n- Loose two")
                }
            }
        }
        val tightOne = onNodeWithText("Tight one").fetchSemanticsNode().boundsInRoot
        val tightTwo = onNodeWithText("Tight two").fetchSemanticsNode().boundsInRoot
        val looseOne = onNodeWithText("Loose one").fetchSemanticsNode().boundsInRoot
        val looseTwo = onNodeWithText("Loose two").fetchSemanticsNode().boundsInRoot
        assertTrue(looseTwo.top - looseOne.bottom > tightTwo.top - tightOne.bottom)
    }

    @Test
    fun tableCellSupportsBrWithoutEnablingHtml() = runDesktopComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                GromozekaTheme {
                    GromozekaMarkdown("| Header |\n| --- |\n| First<br>Second |\n\nSafe<script>alert(1)</script>text")
                }
            }
        }
        val cellNode = onNodeWithText("First", substring = true).fetchSemanticsNode()
        val cell = cellNode.config[SemanticsProperties.Text].joinToString { it.text }
        assertEquals("First\nSecond", cell.trim())
        val header = onNodeWithText("Header", substring = true).fetchSemanticsNode()
        assertTrue(cellNode.boundsInRoot.height > header.boundsInRoot.height * 1.5f)
        onNodeWithText("Safealert(1)text").assertExists()
    }

    @Test
    fun blockquoteUsesParagraphSpacingWithoutExtraEmptyLines() = runDesktopComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                GromozekaTheme {
                    GromozekaMarkdown("> First\n>\n> Second")
                }
            }
        }
        val first = onNodeWithText("First").fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText("Second").fetchSemanticsNode().boundsInRoot
        assertEquals(8f, second.top - first.bottom)
    }

    @Test
    fun invalidHighlightRangesLeaveEveryCodeBlockAndAdjacentTextReadable() = runDesktopComposeUiTest {
        val calls = AtomicInteger()
        val highlighter = CodeHighlightBoundary { code, _, _ ->
            calls.incrementAndGet()
            listOf(ColorHighlight(PhraseLocation(0, code.length + 3), 0xFF00FF))
        }
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalCodeHighlightBoundary provides highlighter) {
                GromozekaTheme {
                    GromozekaMarkdown("Before\n\n    Indented_bad\n\n```kotlin\nFenced_bad\n```\n\n" +
                        "```gromozeka-copy language=\"kotlin\"\nCopyable_bad\n```\n\nAfter")
                }
            }
        }
        for (text in listOf("Before", "Indented_bad", "Fenced_bad", "Copyable_bad", "After")) {
            onNodeWithText(text).assertExists()
        }
        assertTrue(calls.get() >= 3)
    }

    @Test
    fun asynchronousHighlighterExceptionDoesNotEscapeTheConversation() = runDesktopComposeUiTest {
        val calls = AtomicInteger()
        val highlighter = CodeHighlightBoundary { _, _, _ ->
            calls.incrementAndGet()
            throw IllegalStateException("synthetic highlighter failure")
        }
        setContent {
            CompositionLocalProvider(LocalCodeHighlightBoundary provides highlighter) {
                GromozekaTheme { GromozekaMarkdown("Before\n\n```text\nExact original code\n```\n\nAfter") }
            }
        }
        waitUntil(timeoutMillis = 5_000) { calls.get() > 0 }
        waitForIdle()
        onNodeWithText("Exact original code").assertExists()
        onNodeWithText("After").assertExists()
    }

    @Test
    fun parserFailureShowsUnmodifiedSourceInStandaloneView() = runDesktopComposeUiTest {
        val source = "Original **Markdown**\n\n```text\ncode\n```"
        val parser = MarkdownParserBoundary { throw IllegalArgumentException("synthetic parser failure") }
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalMarkdownParserBoundary provides parser) {
                GromozekaTheme { GromozekaMarkdown(source) }
            }
        }
        onNodeWithText(source).assertExists()
    }

    @Test
    fun savedConversationCanReopenAfterParserFailureWithoutDataRepair() = runDesktopComposeUiTest {
        val source = "Saved **unrenderable** message\n\nExact original bytes"
        val message = Conversation.Message(
            id = Conversation.Message.Id("saved-markdown-error"), conversationId = Conversation.Id("saved-conversation"),
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText(source))),
            createdAt = Instant.fromEpochMilliseconds(0),
        )
        val healthy = message.copy(id = Conversation.Message.Id("healthy-neighbor"), content = listOf(
            Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("Healthy neighbor"))))
        val parser = MarkdownParserBoundary { if (it == source) throw IllegalStateException("synthetic parser failure") else parseMarkdown(it) }
        var visible by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalMarkdownParserBoundary provides parser) {
                GromozekaTheme {
                    if (visible) Column {
                        rememberMessageListEntries(listOf(message, healthy), emptyMap(), emptyMap()).forEach {
                            MessageItem(it, loadArtifactContent = { byteArrayOf() })
                        }
                    }
                }
            }
        }
        onNodeWithText(source).assertExists()
        onNodeWithText("Healthy neighbor").assertExists()
        runOnIdle { visible = false }
        onNodeWithText(source).assertDoesNotExist()
        runOnIdle { visible = true }
        onNodeWithText(source).assertExists()
        onNodeWithText("Healthy neighbor").assertExists()
        assertEquals(source, (message.content.single() as Conversation.Message.ContentItem.AssistantMessage).structured.fullText)
    }

    @Test
    fun changingOnlyCodeLanguageRecomputesHighlighting() = runDesktopComposeUiTest {
        val languages = mutableListOf<String?>()
        var language by mutableStateOf("text")
        val highlighter = CodeHighlightBoundary { code, lang, _ ->
            languages += lang
            listOf(ColorHighlight(PhraseLocation(0, code.length), 0x0088FF))
        }
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalCodeHighlightBoundary provides highlighter) {
                GromozekaTheme { GromozekaMarkdown("```$language\nSame source\n```") }
            }
        }
        onNodeWithText("Same source").assertExists()
        runOnIdle { language = "kotlin" }
        onNodeWithText("Same source").assertExists()
        assertTrue("text" in languages)
        assertEquals("kotlin", languages.last())
    }

    @Test
    fun oldHighlightJobCannotReplaceNewShorterCode() = runDesktopComposeUiTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var code by mutableStateOf("Old much longer source")
        val highlighter = CodeHighlightBoundary { source, _, _ ->
            if (source.startsWith("Old")) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            listOf(ColorHighlight(PhraseLocation(0, source.length), 0x0088FF))
        }
        try {
            setContent {
                CompositionLocalProvider(LocalCodeHighlightBoundary provides highlighter) {
                    GromozekaTheme { GromozekaMarkdown("```text\n$code\n```") }
                }
            }
            waitUntil(timeoutMillis = 5_000) { started.count == 0L }
            runOnIdle { code = "New" }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("New").fetchSemanticsNodes().isNotEmpty() }
            release.countDown()
            waitForIdle()
            onNodeWithText("New").assertExists()
            onNodeWithText("Old much longer source").assertDoesNotExist()
        } finally { release.countDown() }
    }

    private fun verifyParagraphSpacing(segmented: Boolean) = runDesktopComposeUiTest {
        val text = "First\n\nSecond\n\nThird"
        val message = Conversation.Message(
            id = Conversation.Message.Id("markdown-test"),
            conversationId = Conversation.Id("markdown-test"),
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Conversation.Message.ContentItem.AssistantMessage(
                Conversation.Message.StructuredText(text),
            )),
            createdAt = Instant.fromEpochMilliseconds(0),
        )
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                GromozekaTheme {
                    if (segmented) {
                        Column {
                            rememberMessageListEntries(listOf(message), emptyMap(), emptyMap()).forEach {
                                MessageItem(it, loadArtifactContent = { byteArrayOf() })
                            }
                        }
                    } else {
                        GromozekaMarkdown(text)
                    }
                }
            }
        }
        val first = onNodeWithText("First").fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText("Second").fetchSemanticsNode().boundsInRoot
        val third = onNodeWithText("Third").fetchSemanticsNode().boundsInRoot
        assertTrue(second.top - first.bottom >= 8f)
        assertEquals(second.top - first.bottom, third.top - second.bottom)
    }
}
