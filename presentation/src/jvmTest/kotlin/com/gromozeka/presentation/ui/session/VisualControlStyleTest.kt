package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.visual.*
import com.gromozeka.presentation.services.theming.data.DarkTheme
import com.gromozeka.presentation.services.theming.data.LightTheme
import com.gromozeka.presentation.ui.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class VisualControlStyleTest {
    @Test fun visualControlsUseTheSameDensityAsTheRestOfTheApplication() = runComposeUiTest {
        val visual = fixture()
        val draft = VisualFormDraft(visual)
        var density by mutableStateOf(UiDensity.COMPACT)
        var dark by mutableStateOf(true)
        setContent {
            GromozekaTheme(currentTheme = if (dark) DarkTheme() else LightTheme(), density = density) {
                Column(Modifier.width(440.dp).height(900.dp)) {
                    CompactButton({}, Modifier.testTag("reference-button"), colors = CompactButtonDefaults.tonalColors(), elevation = null) { Text("Action") }
                    VisualContent(visual, draft, NoRemoteCalls, Modifier.weight(1f))
                }
            }
        }
        for (mode in UiDensity.entries) {
            runOnIdle { density = mode }
            val height = if (mode == UiDensity.COMPACT) 40.dp else 48.dp
            onNodeWithTag("reference-button").assertHeightIsEqualTo(height)
            onNodeWithTag("visual-element-action").assertHeightIsEqualTo(height)
            onNodeWithTag("visual-element-query").assertHeightIsEqualTo(height)
            onNodeWithTag("visual-element-amount").assertHeightIsEqualTo(height)
            onNodeWithTag("visual-element-choice").assertHeightIsEqualTo(height)
            onNodeWithTag("visual-element-notes").assertHeightIsAtLeast(height + 24.dp)
        }
        onNode(hasContentDescription("form.query") and hasSetTextAction()).performTextReplacement("draft remains")
        runOnIdle { density = UiDensity.COMPACT; dark = false }
        onNodeWithTag("visual-element-query").assertHeightIsEqualTo(40.dp)
        runOnIdle {
            assertEquals("draft remains", draft.form.getValue("query").jsonPrimitive.content)
            assertTrue(draft.isFieldDirty("form.query"))
        }
        onNodeWithTag("visual-field-dirty-query").assertExists()
    }

    @Test fun visualButtonReusesSharedShapeAndTonalColorsInBothThemes() = runComposeUiTest {
        val visual = fixture()
        val draft = VisualFormDraft(visual)
        var dark by mutableStateOf(true)
        setContent {
            GromozekaTheme(currentTheme = if (dark) DarkTheme() else LightTheme()) {
                Column(Modifier.width(440.dp).height(900.dp)) {
                    CompactButton({}, Modifier.testTag("reference-button"), colors = CompactButtonDefaults.tonalColors(), elevation = null) { Text("Action") }
                    VisualContent(visual, draft, NoRemoteCalls, Modifier.weight(1f))
                }
            }
        }
        for (theme in listOf(true, false)) {
            runOnIdle { dark = theme }
            val reference = onNodeWithTag("reference-button").captureToImage().toPixelMap()
            val actual = onNodeWithTag("visual-element-action").captureToImage().toPixelMap()
            assertEquals(reference.width, actual.width)
            assertEquals(reference.height, actual.height)
            // Border/corner samples avoid text and verify the shared rounded shape and fill,
            // not just a coincidentally matching minimum height.
            for ((x, y) in listOf(1 to 1, 3 to 3, 1 to reference.height / 2, reference.width / 2 to reference.height - 2)) {
                assertEquals(reference[x, y], actual[x, y], "Shared button style differs at $x,$y (dark=$dark)")
            }
        }
    }

    @Test fun gridDoesNotCollapseTextareaMinimumRows() = runComposeUiTest {
        val original = fixture()
        val visual = original.copy(document = original.document.replace("<div>", "<div layout=\"grid\" columns=\"1fr\">"))
        val draft = VisualFormDraft(visual)
        setContent {
            GromozekaTheme {
                Box(Modifier.width(440.dp).height(900.dp)) { VisualContent(visual, draft, NoRemoteCalls) }
            }
        }
        onNodeWithTag("visual-element-query").assertHeightIsEqualTo(40.dp)
        onNodeWithTag("visual-element-notes").assertHeightIsAtLeast(80.dp)
    }

    private fun fixture(): Visual {
        val markup = """<html><head><title>Styles</title><state-schema><![CDATA[
            {"type":"object","properties":{"form":{"type":"object"},"data":{"type":"object"}},"required":["form","data"]}
            ]]></state-schema></head><body><div>
            <button id="action">Action</button>
            <input id="query" name="form.query"/>
            <input id="amount" name="form.amount" type="number"/>
            <select id="choice" name="form.choice"><option value="one">One</option><option value="two">Two</option></select>
            <textarea id="notes" name="form.notes" rows="3"/>
            </div></body></html>"""
        return Visual("styles", Conversation.Id("conversation"), User.Id("user"), document = markup, title = "Styles",
            state = Json.parseToJsonElement("""{"form":{"query":"text","amount":1,"choice":"one","notes":"notes"},"data":{}}""").jsonObject,
            createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0))
    }

    private object NoRemoteCalls : VisualService {
        override fun observe(conversationId: Conversation.Id) = flowOf(emptyList<Visual>())
        override suspend fun list(conversationId: Conversation.Id) = emptyList<Visual>()
        override suspend fun create(conversationId: Conversation.Id, request: VisualCreate): Visual = error("No remote I/O expected")
        override suspend fun update(conversationId: Conversation.Id, visualId: String, request: VisualUpdate): Visual = error("No remote I/O expected")
        override suspend fun close(conversationId: Conversation.Id, visualId: String) = Unit
        override suspend fun act(conversationId: Conversation.Id, action: VisualAction): VisualActionResult = error("No remote I/O expected")
    }
}
