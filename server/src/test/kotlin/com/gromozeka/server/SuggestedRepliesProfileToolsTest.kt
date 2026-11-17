package com.gromozeka.server

import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.UserProfile.SuggestedRepliesSettings
import com.gromozeka.domain.service.AiCatalogManagementService
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.SettingsService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*

class SuggestedRepliesProfileToolsTest {
    @Test
    fun `existing profile tools read and update both prompts and context without touching device settings`() = runBlocking<Unit> {
        val original = Settings(userProfile = UserProfile(id = UserProfile.Id("saved")), userDeviceSettings = UserDeviceSettings.Desktop())
        var saved = original
        val settings = Mockito.mock(SettingsService::class.java) { invocation ->
            when (invocation.method.name.substringBefore('-')) {
                "getUserProfile" -> saved.userProfile
                "saveSettings" -> {
                    @Suppress("UNCHECKED_CAST")
                    val update = invocation.arguments.single() as Settings.() -> Settings
                    saved = update(saved)
                    Unit
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val tools = AiSettingsToolContributor(testServerToolFactory(), Mockito.mock(AiConfigurationService::class.java),
            Mockito.mock(AiCatalogManagementService::class.java), settings).callbacks
        val read = tools.single { it.definition.name == "grz_user_profile_get" }
        val update = tools.single { it.definition.name == "grz_user_profile_update" }
        val json = Json { encodeDefaults = true }
        val edited = original.userProfile.copy(id = UserProfile.Id("cannot-replace-id"), suggestedRepliesSettings = SuggestedRepliesSettings(
            mode = SuggestedRepliesSettings.Mode.SEPARATE_RUNTIME,
            inlinePrompt = "Custom inline instruction",
            separatePrompt = "Короткие варианты на языке пользователя",
            contextMode = SuggestedRepliesSettings.ContextMode.FULL_THREAD,
        ))
        val args = buildJsonObject { put("userProfile", json.encodeToJsonElement(UserProfile.serializer(), edited)) }
        update.invokeStructured(testControlMcpContext(), args)
        assertEquals(edited.copy(id = original.userProfile.id), saved.userProfile)
        assertEquals(original.userDeviceSettings, saved.userDeviceSettings)
        val result = read.invokeStructured(testControlMcpContext(), buildJsonObject {})
        assertEquals(JsonPrimitive(true), result.getValue("success"))
        val profile = json.decodeFromJsonElement(UserProfile.serializer(), result.getValue("result").jsonObject.getValue("userProfile"))
        assertEquals(saved.userProfile, profile)
        val before = saved
        assertEquals(true, update.invoke(testControlMcpContext(User.Role.entries.first { it != User.Role.OWNER }), args).isError)
        assertEquals(before, saved)
        val invalid = buildJsonObject {
            put("userProfile", JsonObject(json.encodeToJsonElement(UserProfile.serializer(), edited).jsonObject +
                ("suggestedRepliesSettings" to buildJsonObject { put("inlinePrompt", " ") })))
        }
        assertEquals(true, update.invoke(testControlMcpContext(), invalid).isError)
        assertEquals(before, saved)
    }
}
