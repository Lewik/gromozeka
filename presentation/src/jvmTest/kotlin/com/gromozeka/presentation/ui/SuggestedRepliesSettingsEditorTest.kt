package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.UserProfile.SuggestedRepliesSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class SuggestedRepliesSettingsEditorTest {
    private val initial = SuggestedRepliesSettings(inlinePrompt = "INLINE_INITIAL", separatePrompt = "SEPARATE_INITIAL")

    @Test
    fun promptsCanBeEditedIndependentlyInTheSameSettingsGroup() = runDesktopComposeUiTest(width = 800, height = 1200) {
        val settings = mutableStateOf(initial)
        setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SuggestedRepliesSettingsEditor(settings.value) { settings.value = it }
                }
            }
        }
        onNodeWithText("Inline suggestions prompt").assertIsDisplayed()
        onNodeWithText("Separate suggestions prompt").assertIsDisplayed()
        onNodeWithText("INLINE_INITIAL").performTextReplacement("Короткие варианты на языке пользователя.")
        runOnIdle {
            assertEquals("Короткие варианты на языке пользователя.", settings.value.inlinePrompt)
            assertEquals("SEPARATE_INITIAL", settings.value.separatePrompt)
        }
        onNodeWithText("SEPARATE_INITIAL").performTextReplacement("Separate custom instruction")
        runOnIdle {
            assertEquals("Separate custom instruction", settings.value.separatePrompt)
            assertEquals("Короткие варианты на языке пользователя.", settings.value.inlinePrompt)
        }
    }

    @Test
    fun emptyDraftShowsValidationInsteadOfPersistingAnInvalidProfile() = runDesktopComposeUiTest(width = 800, height = 1200) {
        val settings = mutableStateOf(initial)
        setContent { MaterialTheme { SuggestedRepliesSettingsEditor(settings.value) { settings.value = it } } }
        onNodeWithText("INLINE_INITIAL").performTextClearance()
        onNodeWithText("Prompt must not be empty.").assertIsDisplayed()
        runOnIdle { assertEquals(initial, settings.value) }
        onNode(hasSetTextAction() and hasText("Inline suggestions prompt")).performTextReplacement("Valid replacement")
        runOnIdle { assertEquals("Valid replacement", settings.value.inlinePrompt) }
        onNodeWithText("Prompt must not be empty.").assertDoesNotExist()
    }

    @Test
    fun changingGenerationAndContextModesPreservesBothPrompts() = runDesktopComposeUiTest(width = 800, height = 1200) {
        val settings = mutableStateOf(initial)
        setContent { MaterialTheme { SuggestedRepliesSettingsEditor(settings.value) { settings.value = it } } }
        onNodeWithText("Inline with the answer").performClick()
        onNodeWithText("Separate AI runtime").performClick()
        onNodeWithText("Full current thread").performClick()
        onNodeWithText("Recent messages (10 × 4000 characters)").performClick()
        runOnIdle {
            assertEquals(initial.copy(mode = SuggestedRepliesSettings.Mode.SEPARATE_RUNTIME,
                contextMode = SuggestedRepliesSettings.ContextMode.RECENT_MESSAGES), settings.value)
        }
        onNodeWithText("Recent messages (10 × 4000 characters)").performClick()
        onNodeWithText("Full current thread").performClick()
        runOnIdle {
            assertEquals(initial.copy(mode = SuggestedRepliesSettings.Mode.SEPARATE_RUNTIME,
                contextMode = SuggestedRepliesSettings.ContextMode.FULL_THREAD), settings.value)
        }
        onNodeWithText("Full current thread").assertIsDisplayed()
    }
}
