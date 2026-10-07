package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.visual.*
import com.gromozeka.presentation.ui.GromozekaTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class VisualHalfSpacingTest {
    @Test fun nestedContainersDoNotAccumulateInsetsAndInlineContentIsOneBlock() = runComposeUiTest {
        val visual = visual("<div><div><div><p id='first'>Alpha <span format='bold'>one</span></p></div></div></div><p id='second'>Beta</p>Bare text")
        val draft = VisualFormDraft(visual)
        setContent { GromozekaTheme { Box(Modifier.size(420.dp, 500.dp)) { VisualContent(visual, draft, NoIo) } } }
        val first = onNodeWithTag("visual-element-first", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val second = onNodeWithTag("visual-element-second", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bare = onNodeWithText("Bare text").fetchSemanticsNode().boundsInRoot
        val fullSpace = with(density) { 8.dp.toPx() }
        assertEquals(fullSpace + with(density) { 4.dp.toPx() }, first.left)
        assertEquals(fullSpace, first.top)
        assertEquals(fullSpace, second.top - first.bottom)
        assertEquals(fullSpace, bare.top - second.bottom)
        assertEquals(first.left, second.left)
        assertEquals(first.height, second.height, "Inline spans must not add their own margins")
    }

    @Test fun gridFramesTouchWhileColorBorderAndHighlightDoNotMoveContent() = runComposeUiTest {
        val original = visual("<div layout='grid' columns='1fr 1fr'><div id='cell-left'><p id='left'>Left</p></div><div id='cell-right'><button id='right'>Right</button></div></div>")
        var current by mutableStateOf(original)
        var highlighted by mutableStateOf(emptySet<String>())
        val draft = VisualFormDraft(original)
        setContent { GromozekaTheme { Box(Modifier.size(420.dp, 500.dp)) { VisualContent(current, draft, NoIo, highlightedIds = highlighted) } } }
        val ids = listOf("cell-left", "cell-right", "left", "right")
        fun bounds(id: String) = onNodeWithTag("visual-element-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val before = ids.associateWith(::bounds)
        assertEquals(before.getValue("cell-left").right, before.getValue("cell-right").left)
        assertEquals(with(density) { 4.dp.toPx() }, before.getValue("left").left - before.getValue("cell-left").left)
        assertEquals(with(density) { 40.dp.toPx() }, before.getValue("right").height, "Native button interior size must stay unchanged")
        runOnIdle {
            current = original.copy(document = original.document
                .replace("id='cell-left'", "id='cell-left' border='true' background='blue'")
                .replace("id='cell-right'", "id='cell-right' border='true'"), documentRevision = 2, revision = 2)
            highlighted = setOf("cell-left", "right")
        }
        waitForIdle()
        ids.forEach { assertEquals(before.getValue(it), bounds(it), "Decoration must not affect spacing: $it") }
    }

    private fun visual(body: String): Visual {
        val state = Json.parseToJsonElement("""{"form":{},"data":{}}""").jsonObject
        val document = """<html><head><title>Spacing</title><state-schema><![CDATA[{"type":"object","properties":{"form":{"type":"object"},"data":{"type":"object"}},"required":["form","data"]}]]></state-schema></head><body>$body</body></html>"""
        val now = Instant.fromEpochMilliseconds(0)
        return Visual("spacing", Conversation.Id("conversation"), User.Id("user"), document = document, title = "Spacing", state = state, createdAt = now, updatedAt = now)
    }

    private object NoIo : VisualService {
        override fun observe(conversationId: Conversation.Id) = flowOf(emptyList<Visual>())
        override suspend fun list(conversationId: Conversation.Id) = emptyList<Visual>()
        override suspend fun create(conversationId: Conversation.Id, request: VisualCreate): Visual = error("No I/O")
        override suspend fun update(conversationId: Conversation.Id, visualId: String, request: VisualUpdate): Visual = error("No I/O")
        override suspend fun close(conversationId: Conversation.Id, visualId: String) = Unit
        override suspend fun act(conversationId: Conversation.Id, action: VisualAction): VisualActionResult = error("No I/O")
    }
}
