package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.services.translation.data.EnglishTranslation
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.LocalTranslation
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandToolPresentationTest {
    private val translation = EnglishTranslation()

    @Test
    fun descriptionIsTheStableTitleAndTechnicalDetailsAppearOnlyWhenExpanded() = runComposeUiTest {
        val description = "Проверить сборку"
        val command = "printf 'original'\nprintf 'second line'"
        val name = "grz_execute_command__v9"
        val state = mutableStateOf(ActivityState.RUNNING)
        setContent {
            Invocation(Conversation.Message.ContentItem.ToolCall.Data(name, buildJsonObject {
                put("command", command)
                put("description", description)
                put("working_directory", "/workspace/project")
                put("timeout_seconds", 30)
            }), state.value)
        }
        for (activity in ActivityState.entries) {
            runOnIdle { state.value = activity }
            onNodeWithText(description).assertIsDisplayed()
            onNodeWithText(name).assertDoesNotExist()
            onNodeWithText(translation.text("chat.tool.command", "command" to command), substring = true).assertDoesNotExist()
        }
        onNodeWithTag("command-header").performClick()
        onNodeWithText(name).assertIsDisplayed()
        onNodeWithText(translation.text("chat.tool.command", "command" to command), substring = true).assertIsDisplayed()
        onNodeWithText(translation.text("chat.tool.workingDirectory", "path" to "project"), substring = true).assertIsDisplayed()
        onNodeWithText(translation.text("chat.tool.timeout", "seconds" to 30), substring = true).assertIsDisplayed()
        onNodeWithTag("command-header").performClick()
        onNodeWithText(name).assertDoesNotExist()
        onNodeWithText(description).assertIsDisplayed()
    }

    @Test
    fun missingBlankAndNonStringDescriptionsUseTheCommandWithoutAToolNamePrefix() = runComposeUiTest {
        val input = mutableStateOf(buildJsonObject { put("command", "echo fallback") })
        setContent { Invocation(Conversation.Message.ContentItem.ToolCall.Data("grz_execute_command", input.value), ActivityState.RUNNING) }
        for (description in listOf<JsonElement?>(null, JsonPrimitive(""), JsonPrimitive(" \n "), JsonPrimitive(42))) {
            runOnIdle {
                input.value = buildJsonObject {
                    put("command", "echo fallback")
                    description?.let { put("description", it) }
                }
            }
            onNodeWithText("echo fallback").assertIsDisplayed()
            onNodeWithText("grz_execute_command: echo fallback").assertDoesNotExist()
        }
    }

    @Test
    fun longDescriptionsStaySingleLineAndDoNotFallBackToShellText() = runComposeUiTest {
        val description = mutableStateOf("Проверить сборку")
        setContent {
            Invocation(Conversation.Message.ContentItem.ToolCall.Data("grz_execute_command__v9", buildJsonObject {
                put("command", "echo original")
                put("description", description.value)
            }), ActivityState.COMPLETE)
        }
        val initialHeight = onNodeWithText(description.value).fetchSemanticsNode().boundsInRoot.height
        runOnIdle { description.value = "Проверка: " + "д".repeat(900) }
        onNodeWithText(description.value).assertIsDisplayed()
        assertEquals(initialHeight, onNodeWithText(description.value).fetchSemanticsNode().boundsInRoot.height)
        onNodeWithText("echo original").assertDoesNotExist()
    }

    @Composable
    private fun Invocation(call: Conversation.Message.ContentItem.ToolCall.Data, state: ActivityState) {
        var expanded by remember { mutableStateOf(false) }
        CompositionLocalProvider(LocalTranslation provides translation) {
            GromozekaTheme {
                Box(Modifier.size(390.dp, 500.dp)) {
                    ToolCallItem(call, null, "/workspace", expanded, { expanded = !expanded }, state,
                        Modifier.testTag("command-header"), loadArtifactContent = { byteArrayOf() })
                }
            }
        }
    }
}
