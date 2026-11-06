package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.GromozekaTheme
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class MessageTextSelectionTest {
    @Test
    fun partialSelectionSpansInlineFormattingWithoutSelectingTheWholeLine() = runDesktopComposeUiTest {
        val clipboard = MemoryClipboard()
        showMessage("Alpha **bold** `code` *italic* Omega", Conversation.Message.Role.ASSISTANT, clipboard)
        val text = onNodeWithText("Alpha", substring = true, useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        val start = text.indexOf("bold")
        val end = text.indexOf(" Omega")
        assertTrue(start > 0 && end > start, "Unexpected rendered inline text: $text")
        copyRange(text, start, text, end)
        runOnIdle {
            assertEquals(text.substring(start, end), clipboard.text)
            assertTrue(clipboard.text.orEmpty().contains("code"))
        }
    }

    @Test
    fun assistantSelectionSpansParagraphHeadingAndList() = verifyFormattedMessage(Conversation.Message.Role.ASSISTANT)

    @Test
    fun userSelectionSpansParagraphHeadingAndList() = verifyFormattedMessage(Conversation.Message.Role.USER)

    @Test
    fun selectionIncludesCodeBetweenParagraphs() = runDesktopComposeUiTest {
        val clipboard = MemoryClipboard()
        showMessage("Before code\n\n```text\nval answer = 42\n```\n\nAfter code", Conversation.Message.Role.ASSISTANT, clipboard)
        copyRange("Before code", 0, "After code", "After code".length)
        runOnIdle { assertInOrder(clipboard.text.orEmpty(), listOf("Before code", "val answer = 42", "After code")) }
    }

    private fun verifyFormattedMessage(role: Conversation.Message.Role) = runDesktopComposeUiTest {
        val clipboard = MemoryClipboard()
        showMessage("Alpha **bold** start.\n\n## Middle heading\n\n- First item\n- Second item\n\nOmega *italic* end.", role, clipboard)
        copyRange("Alpha bold start.", 0, "Omega italic end.", "Omega italic end.".length)
        runOnIdle {
            assertInOrder(clipboard.text.orEmpty(), listOf("Alpha bold start.", "Middle heading", "First item", "Second item", "Omega italic end."))
        }
    }

    private fun ComposeUiTest.showMessage(source: String, role: Conversation.Message.Role, clipboard: MemoryClipboard) {
        val content = if (role == Conversation.Message.Role.USER) Conversation.Message.ContentItem.UserMessage(source)
            else Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText(source))
        val message = Conversation.Message(Conversation.Message.Id("message"), Conversation.Id("conversation"),
            role = role, content = listOf(content), createdAt = Instant.fromEpochMilliseconds(0))
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalClipboard provides clipboard) {
                GromozekaTheme {
                    val entries = rememberMessageListEntries(listOf(message), emptyMap(), emptyMap())
                    LazyColumn(Modifier.fillMaxSize().testTag("selection-host")) {
                        items(entries, key = { it.key }) { MessageItem(it, loadArtifactContent = { byteArrayOf() }) }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.copyRange(first: String, start: Int, last: String, end: Int) {
        val host = onNodeWithTag("selection-host")
        val origin = host.fetchSemanticsNode().boundsInRoot.topLeft
        fun point(text: String, offset: Int, trailing: Boolean): Offset {
            val node = onNodeWithText(text, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val character = layouts.single().getBoundingBox(offset)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            return Offset(bounds.left + (if (trailing) character.right - 0.5f else character.left + 0.5f),
                bounds.top + character.center.y) - origin
        }
        val from = point(first, start, false)
        val to = point(last, end - 1, true)
        host.performMouseInput { moveTo(from); press(); moveTo(to); release() }
        val modifier = if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) Key.MetaLeft else Key.CtrlLeft
        host.performKeyInput { keyDown(modifier); pressKey(Key.C); keyUp(modifier) }
    }

    private fun assertInOrder(actual: String, parts: List<String>) {
        var after = 0
        parts.forEach { part ->
            val index = actual.indexOf(part, after)
            assertTrue(index >= after, "Missing or reordered '$part' in copied text: $actual")
            after = index + part.length
        }
    }

    private class MemoryClipboard : Clipboard {
        private var entry: ClipEntry? = null
        override val nativeClipboard = java.awt.datatransfer.Clipboard("message-selection-test")
        val text: String? get() = entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
        override suspend fun getClipEntry() = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
            nativeClipboard.setContents(clipEntry?.asAwtTransferable ?: StringSelection(""), null)
        }
    }
}
