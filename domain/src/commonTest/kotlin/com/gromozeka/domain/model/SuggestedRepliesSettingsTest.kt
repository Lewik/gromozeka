package com.gromozeka.domain.model

import com.gromozeka.domain.model.UserProfile.SuggestedRepliesSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SuggestedRepliesSettingsTest {
    @Test
    fun defaultPromptsShareTheSoftTotalLengthRecommendationWithoutTheTechnicalPrefix() {
        val settings = SuggestedRepliesSettings()
        assertEquals(settings.inlinePrompt, settings.separatePrompt)
        val reference = "suggestedReplies: zero to four concise replies the user might naturally send next. Keep it empty during tool use"
        assertEquals(112, reference.length)
        assertContains(settings.inlinePrompt, "recommended not to exceed ${reference.length} characters in total")
        assertContains(settings.inlinePrompt, "preserve the user's language")
        assertContains(settings.inlinePrompt, "zero to four")
        assertFalse("suggestedReplies:" in settings.inlinePrompt)
        assertEquals(SuggestedRepliesSettings.ContextMode.RECENT_MESSAGES, settings.contextMode)
    }

    @Test
    fun promptsCannotBeBlank() {
        for (blank in listOf("", " \n\t")) {
            assertFailsWith<IllegalArgumentException> { SuggestedRepliesSettings(inlinePrompt = blank) }
            assertFailsWith<IllegalArgumentException> { SuggestedRepliesSettings(separatePrompt = blank) }
        }
    }

    @Test
    fun profileSerializationExposesIndependentPromptsAndContextWithoutLosingOtherSettings() {
        val profile = UserProfile(
            id = UserProfile.Id("saved-profile"),
            displayName = "Lev",
            suggestedRepliesSettings = SuggestedRepliesSettings(
                mode = SuggestedRepliesSettings.Mode.SEPARATE_RUNTIME,
                inlinePrompt = "Короткие варианты в основном ответе.",
                separatePrompt = "Separate generation instruction.",
                contextMode = SuggestedRepliesSettings.ContextMode.FULL_THREAD,
            ),
        )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(UserProfile.serializer(), profile)
        val settings = Json.parseToJsonElement(encoded).jsonObject.getValue("suggestedRepliesSettings").jsonObject
        assertEquals(profile.suggestedRepliesSettings.inlinePrompt, settings.getValue("inlinePrompt").jsonPrimitive.content)
        assertEquals(profile.suggestedRepliesSettings.separatePrompt, settings.getValue("separatePrompt").jsonPrimitive.content)
        assertEquals("FULL_THREAD", settings.getValue("contextMode").jsonPrimitive.content)
        assertEquals(profile, json.decodeFromString(UserProfile.serializer(), encoded))
    }

    @Test
    fun existingSavedModeKeepsItsValueWhenNewPreferencesAreNotYetStored() {
        val profile = Json.decodeFromString<UserProfile>("""{"id":"saved","displayName":"Lev","suggestedRepliesSettings":{"mode":"SEPARATE_RUNTIME"}}""")
        assertEquals(UserProfile.Id("saved"), profile.id)
        assertEquals("Lev", profile.displayName)
        assertEquals(SuggestedRepliesSettings.Mode.SEPARATE_RUNTIME, profile.suggestedRepliesSettings.mode)
        assertEquals(SuggestedRepliesSettings.DEFAULT_PROMPT, profile.suggestedRepliesSettings.inlinePrompt)
        assertEquals(SuggestedRepliesSettings.DEFAULT_PROMPT, profile.suggestedRepliesSettings.separatePrompt)
        assertEquals(SuggestedRepliesSettings.ContextMode.RECENT_MESSAGES, profile.suggestedRepliesSettings.contextMode)
    }
}
