package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
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
