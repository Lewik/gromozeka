package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.visual.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class VisualDirtyIndicatorTest {
    @Test fun dirtyDotHasNoClickOrTooltipAndDoesNotMoveTitleOrClose() = runComposeUiTest {
        val dirty = mutableStateOf(false)
        var closes = 0
        mainClock.autoAdvance = false
        setContent { MaterialTheme { VisualTabTitle("test", "Preview", dirty.value) { closes++ } } }
        val titleBounds = bounds("visual-title-test")
        val closeBounds = bounds("visual-close-test")
        onNodeWithTag("visual-dirty-test").assertDoesNotExist()
        runOnIdle { dirty.value = true }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("visual-dirty-test").assertExists().assertHasNoClickAction()
        onNodeWithTag("visual-dirty-test").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Local changes",
        ))
        assertEquals(titleBounds, bounds("visual-title-test"))
        assertEquals(closeBounds, bounds("visual-close-test"))
        assertBadgeInside("visual-dirty-test", titleBounds, LayoutDirection.Ltr)
        onNodeWithTag("visual-dirty-test").performMouseInput { enter(center) }
        mainClock.advanceTimeBy(1_000)
        onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag("visual-close-test").performClick()
        runOnIdle { assertEquals(1, closes); dirty.value = false }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("visual-dirty-test").assertDoesNotExist()
        assertEquals(closeBounds, bounds("visual-close-test"))
    }

    @Test fun tightConstraintsDoNotStretchTheDotOrChangeTheAnchorAndRtlMirrorsIt() {
        for (direction in LayoutDirection.entries) runComposeUiTest {
            val dirty = mutableStateOf(false)
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    MaterialTheme {
                        Box(Modifier.requiredSize(280.dp, 72.dp).clipToBounds()) {
                            VisualDirtyBadge(dirty.value, "badge", Modifier.fillMaxSize().testTag("wrapper")) {
                                Box(Modifier.fillMaxSize().testTag("anchor"))
                            }
                        }
                    }
                }
            }
            val before = bounds("anchor")
            runOnIdle { dirty.value = true }
            waitForIdle()
            assertEquals(before, bounds("anchor"))
            assertBadgeInside("badge", before, direction)
            runOnIdle { dirty.value = false }
            waitForIdle()
            onNodeWithTag("badge").assertDoesNotExist()
            assertEquals(before, bounds("anchor"))
        }
    }

    @Test fun everyNativeFieldGetsItsOwnBadgeWithoutMovingGridCellsOrSubmit() = runComposeUiTest {
        val visual = fixture()
        val draft = VisualFormDraft(visual)
        setContent {
            MaterialTheme {
                Column(Modifier.width(420.dp).height(900.dp).clipToBounds()) {
                    VisualTabTitle(visual.id, visual.title, draft.hasLocalChanges) {}
                    VisualContent(visual, draft, UnusedService, Modifier.weight(1f))
                }
            }
        }
        val fields = listOf("query", "amount", "enabled", "choice", "volume", "notes")
        val before = fields.associateWith { bounds("visual-element-$it") }
        val submitBefore = bounds("visual-element-save")
        fields.forEach { onNodeWithTag("visual-field-dirty-$it").assertDoesNotExist() }
        onNode(hasContentDescription("form.query") and hasSetTextAction()).performTextReplacement("local")
        onNodeWithTag("visual-element-enabled").performClick()
        runOnIdle {
            draft.edit(visual, "form.amount", JsonPrimitive("2"), numberEditor = true)
            draft.edit(visual, "form.choice", JsonPrimitive("two"))
            draft.edit(visual, "form.volume", JsonPrimitive(6))
            draft.edit(visual, "form.notes", JsonPrimitive("local notes"))
        }
        waitForIdle()
        for (id in fields) {
            onNodeWithTag("visual-field-dirty-$id").assertExists().assertHasNoClickAction()
            assertEquals(before.getValue(id), bounds("visual-element-$id"), id)
            // Checkbox/slider semantics describe the glyph/track, while their native
            // layout anchor also includes Material's minimum interactive touch area.
            assertBadgeInside("visual-field-dirty-$id", bounds("visual-field-container-$id"), LayoutDirection.Ltr)
        }
        assertEquals(submitBefore, bounds("visual-element-save"))
        onNodeWithTag("visual-dirty-${visual.id}").assertExists()
        runOnIdle { draft.edit(visual, "form.amount", JsonPrimitive("1.0"), numberEditor = true) }
        waitForIdle()
        onNodeWithTag("visual-field-dirty-amount").assertDoesNotExist()
        onNodeWithTag("visual-field-dirty-query").assertExists()
        runOnIdle {
            val accepted = draft.form
            draft.submitted("accepted-fields", draft.state(visual))
            draft.accept(visual.copy(formRevision = 2,
                formEventId = "accepted-fields",
                state = buildJsonObject { put("form", accepted); put("data", buildJsonObject {}) }))
        }
        waitForIdle()
        fields.forEach { onNodeWithTag("visual-field-dirty-$it").assertDoesNotExist() }
        onNodeWithTag("visual-dirty-${visual.id}").assertDoesNotExist()
        assertEquals(submitBefore, bounds("visual-element-save"))
    }

    private fun ComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.assertBadgeInside(tag: String, anchor: Rect, direction: LayoutDirection) {
        val badge = bounds(tag)
        assertTrue(badge.width in 5f..8f && badge.height in 5f..8f, "Expected a small dot, got $badge")
        assertTrue(badge.left >= anchor.left && badge.top >= anchor.top && badge.right <= anchor.right && badge.bottom <= anchor.bottom,
            "Badge $badge must stay inside $anchor")
        assertTrue(badge.top <= anchor.top + 1f)
        if (direction == LayoutDirection.Ltr) assertTrue(badge.right >= anchor.right - 1f)
        else assertTrue(badge.left <= anchor.left + 1f)
    }

    private fun fixture(): Visual {
        val document = """<html><head><title>Form</title><state-schema><![CDATA[
            {"type":"object","properties":{"form":{"type":"object"},"data":{"type":"object"}},"required":["form","data"]}
            ]]></state-schema></head><body><div layout="grid" columns="auto 1fr">
            <label for="query">Query</label><input id="query" name="form.query"/>
            <label for="amount">Amount</label><input id="amount" name="form.amount" type="number"/>
            <label for="enabled">Enabled</label><input id="enabled" name="form.enabled" type="checkbox"/>
            <label for="choice">Choice</label><select id="choice" name="form.choice"><option value="one">One</option><option value="two">Two</option></select>
            <label for="volume">Volume</label><input id="volume" name="form.volume" type="range" min="0" max="10"/>
            <label for="notes">Notes</label><textarea id="notes" name="form.notes" rows="2"/>
            </div><button id="save">Submit</button></body></html>"""
        return Visual("test", Conversation.Id("conversation"), User.Id("user"), document = document, title = "Form",
            state = Json.parseToJsonElement("""{"form":{"query":"saved","amount":1,"enabled":true,"choice":"one","volume":5,"notes":"notes"},"data":{}}""").jsonObject,
            createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0))
    }

    private object UnusedService : VisualService {
        override fun observe(conversationId: Conversation.Id) = flowOf(emptyList<Visual>())
        override suspend fun list(conversationId: Conversation.Id) = emptyList<Visual>()
        override suspend fun create(conversationId: Conversation.Id, request: VisualCreate): Visual = error("No remote I/O in a field test")
        override suspend fun update(conversationId: Conversation.Id, visualId: String, request: VisualUpdate): Visual = error("No remote I/O in a field test")
        override suspend fun close(conversationId: Conversation.Id, visualId: String) = Unit
        override suspend fun act(conversationId: Conversation.Id, action: VisualAction): VisualActionResult = error("No submission expected")
    }
}
