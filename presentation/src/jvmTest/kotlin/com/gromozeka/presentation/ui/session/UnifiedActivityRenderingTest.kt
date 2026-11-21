package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.presentation.services.translation.data.EnglishTranslation
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class UnifiedActivityRenderingTest {
    @Test fun mobileGroupsIdenticalRunningToolsWithoutCaptionsOrStatusTotals() = runDesktopComposeUiTest(width = 390, height = 300) {
        val message = activityTestMessage("parallel", *calls().toTypedArray())
        setContent { ActivityTimelineFixture(listOf(message), emptyMap(), isCompactLayout = true) }
        onNodeWithContentDescription("Execute Command: 4", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("Read File: 2", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("4").assertIsDisplayed()
        onNodeWithText("2").assertIsDisplayed()
        onNodeWithText("Run validation 1").assertDoesNotExist()
        onNodeWithText("Currently running").assertDoesNotExist()
        onNodeWithTag(UiTestTag.ActivityGroup("parallel:0:content").value).performClick()
        onNodeWithTag(UiTestTag.ActivityItem("parallel:0:content").value).assertIsDisplayed()
    }

    @Test fun desktopKeepsEveryRunningIconAndShrinksTheLastCaptionsFirst() = runDesktopComposeUiTest(width = 820, height = 200) {
        val message = activityTestMessage("six", *(1..6).map { command(it, "Run a deliberately long validation stage $it") }.toTypedArray())
        setContent { ActivityTimelineFixture(listOf(message), emptyMap()) }
        val icons = onAllNodesWithContentDescription("Execute Command: 1", useUnmergedTree = true)
        icons.assertCountEquals(6)
        val boxes = icons.fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.left }
        boxes.zipWithNext().forEach { (left, right) -> assertTrue(right.left - left.right >= activityIconSize.value - 1) }
        assertTrue(boxes.all { abs(it.top - boxes.first().top) < 1 })
        onNodeWithText("Run a deliberately long validation stage 1", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Run a deliberately long validation stage 6", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun expandedGroupSeparatesCompletedProblemsAndCurrentCallsWithoutChangingSourceOrderInsideSections() = runDesktopComposeUiTest(width = 800, height = 600) {
        val message = activityTestMessage("mixed-states", command(0), command(1), command(2), command(3))
        val results = activityTestResults(listOf(message)).filterKeys { it == "call-1" || it == "call-2" }
            .mapValues { (key, value) -> value.copy(isError = key == "call-2") }
        var timeline = emptyList<ConversationTimelineItem>()
        setContent { ActivityTimelineFixture(listOf(message), results, onTimeline = { timeline = it }) }
        onNodeWithTag(UiTestTag.ActivityGroup("mixed-states:0:content").value).performClick()
        onNodeWithTag("activity-divider:mixed-states:0:content:problems").assertIsDisplayed()
        onNodeWithTag("activity-divider:mixed-states:0:content:running").assertIsDisplayed()
        runOnIdle {
            val source = (timeline.filterIsInstance<ConversationTimelineItem.Message>().first().entry.segment as MessageSegment.ActivityGroup)
            assertEquals(listOf(0, 1, 2, 3), source.activities.map { it.contentIndex })
            val children = timeline.filterIsInstance<ConversationTimelineItem.Message>().filter { it.entry.activityDepth > 0 }
            assertEquals(listOf(1, 2, 0, 3), children.map { (it.entry.segment as MessageSegment.Activity).contentIndex })
            val dividers = timeline.filterIsInstance<ConversationTimelineItem.ActivityDivider>()
            assertEquals(listOf(ActivityGroupSection.PROBLEMS, ActivityGroupSection.RUNNING), dividers.map { it.before })
            assertEquals(timeline.size, timeline.map { it.key }.distinct().size)
        }
    }

    @Test fun terminalStatesRemainSeparateAndVisibleWhenTheGroupIsCollapsed() = runDesktopComposeUiTest {
        val message = activityTestMessage("terminal-states", activityTestCall("complete"), activityTestCall("failed"), activityTestCall("interrupted"))
        val results = activityTestResults(listOf(message)).mapValues { (key, value) -> when (key) {
            "failed" -> value.copy(isError = true)
            "interrupted" -> value.copy(isError = true, state = Conversation.Message.BlockState.INTERRUPTED)
            else -> value
        } }
        setContent { ActivityTimelineFixture(listOf(message), results, isCompactLayout = true) }
        onAllNodesWithContentDescription("Read File: 1", useUnmergedTree = true).assertCountEquals(3)
        val translation = EnglishTranslation()
        onNodeWithContentDescription(translation.text("runtime.failedTaskLabel"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription(translation.text("chat.activity.interrupted"), useUnmergedTree = true).assertIsDisplayed()
        val groupDescription = listOf(
            translation.text("chat.activity.groupCount", "count" to 3),
            translation.text("runtime.failedTaskLabel"),
            translation.text("chat.activity.interrupted"),
        ).joinToString(" · ")
        onNodeWithContentDescription(groupDescription).assertIsDisplayed()
        onNodeWithTag(UiTestTag.ActivityGroup("terminal-states:0:content").value).performClick()
        onNodeWithTag("activity-divider:terminal-states:0:content:problems").assertIsDisplayed()
    }

    @Test fun modelRequestIsACompactUnlabelledTransientIndicator() = runDesktopComposeUiTest(width = 390, height = 200) {
        val request = mutableStateOf<ModelRequestActivity?>(request())
        var timeline = emptyList<ConversationTimelineItem>()
        setContent { ActivityTimelineFixture(emptyList(), modelRequest = request.value, onTimeline = { timeline = it }) }
        val ring = onNodeWithContentDescription("Calling model", useUnmergedTree = true)
        ring.assertIsDisplayed()
        val bounds = ring.getBoundsInRoot()
        assertEquals(14.dp, bounds.right - bounds.left)
        assertEquals(14.dp, bounds.bottom - bounds.top)
        onNodeWithText("Calling model").assertDoesNotExist()
        runOnIdle { assertTrue(timeline.single() is ConversationTimelineItem.ModelRequest); request.value = null }
        onNodeWithTag("model-request:generation").assertDoesNotExist()
        runOnIdle { assertTrue(timeline.isEmpty()) }
    }

    @Test fun hoverCoversPaddingButNotTheRoundedCorner() = runDesktopComposeUiTest(width = 800, height = 200) {
        val message = activityTestMessage("hover", command(0), command(1))
        setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { ActivityTimelineFixture(listOf(message), emptyMap()) } } }
        val node = onNodeWithTag(UiTestTag.ActivityGroup("hover:0:content").value)
        val idle = node.captureToImage().toPixelMap()
        node.performMouseInput { enter(center) }
        mainClock.advanceTimeBy(400)
        waitForIdle()
        val hovered = node.captureToImage().toPixelMap()
        fun difference(x: Int, y: Int): Float = abs(idle[x, y].red - hovered[x, y].red) +
            abs(idle[x, y].green - hovered[x, y].green) + abs(idle[x, y].blue - hovered[x, y].blue)
        assertTrue(difference(2, idle.height / 2) > 0.02f, "Hover must include the outer left padding")
        assertTrue(difference(idle.width / 2, 1) > 0.02f, "Hover must include the top padding")
        assertTrue(difference(0, 0) < 0.02f, "The rounded corner stays outside the indication")
    }

    @Test fun finishingTheModelKeepsASingleActivityGroupAndItsOpenDetails() = runDesktopComposeUiTest(width = 800, height = 400) {
        val owner = AgentDefinition.Id("agent")
        val root = activityTestMessage("root", Conversation.Message.ContentItem.UserMessage("Continue")).copy(role = Conversation.Message.Role.USER)
        val work = activityTestMessage("one", command(0)).copy(author = Conversation.Message.Author.Agent(owner, "Agent"))
        val active = mutableStateOf<ModelRequestActivity?>(request().copy(owner = owner, rootUserMessageId = root.id))
        setContent { ActivityTimelineFixture(listOf(root, work), modelRequest = active.value) }
        val group = onNodeWithTag(UiTestTag.ActivityGroup("one:0:content").value)
        group.performClick()
        onNodeWithTag(UiTestTag.ActivityItem("one:0:content").value).performClick()
        onNodeWithText("Tool output call-0").assertIsDisplayed()
        runOnIdle { active.value = null }
        group.assertIsDisplayed()
        onNodeWithText("Tool output call-0").assertIsDisplayed()
        onNodeWithTag("model-request:generation").assertDoesNotExist()
    }

    @Test fun captionsUseTheirNaturalHeightAtLargeFontScale() = runDesktopComposeUiTest(width = 960, height = 200) {
        val message = activityTestMessage("large-font", command(0), command(1))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ActivityTimelineFixture(listOf(message), emptyMap())
            }
        }
        val caption = onNodeWithText("Run validation 0", useUnmergedTree = true).getBoundsInRoot()
        val group = onNodeWithTag(UiTestTag.ActivityGroup("large-font:0:content").value).getBoundsInRoot()
        assertTrue(caption.bottom - caption.top >= 32.dp, "Scaled captions must not be squeezed into the 28dp icon slot")
        assertTrue(group.bottom - group.top >= caption.bottom - caption.top + 8.dp)
    }

    private fun calls() = (1..6).map { index -> if (index % 3 == 2) activityTestCall("call-$index") else command(index) }
    private fun command(index: Int, title: String = "Run validation $index") = activityTestCall("call-$index").copy(
        call = Conversation.Message.ContentItem.ToolCall.Data("grz_execute_command__v12", buildJsonObject {
            put("command", JsonPrimitive("echo $index")); put("description", JsonPrimitive(title))
        }))
    private fun request(): ModelRequestActivity {
        val now = Instant.fromEpochSeconds(100)
        return ModelRequestActivity(ActiveGenerationSnapshot("generation", Conversation.Id("activity-test"), ConversationRuntimeTask.Id("task"),
            "test-provider", "test-model", 1, ActiveGenerationSnapshot.Phase.WAITING_FOR_MODEL, now, now, 0, 0, 0, 0),
            null, null, ConversationIndicatorState(ConversationActivity.WORKING, ConversationPressure.NORMAL, false, false, emptySet()), "Calling model")
    }
}
