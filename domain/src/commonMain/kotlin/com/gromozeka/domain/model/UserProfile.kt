package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Durable profile for one Gromozeka user.
 *
 * This object owns user-level preferences. Sections that are meaningful only as
 * user settings should stay nested here instead of becoming artificial global
 * domain roots.
 */
@Serializable
data class UserProfile(
    val id: Id = Id("local"),
    val displayName: String = "Local user",
    val aiSettings: AiSettings = UserProfileAiDefaults.aiSettings(),
    val speechSettings: SpeechSettings = SpeechSettings(),
    val agentSettings: AgentSettings = AgentSettings(),
    val memorySettings: MemorySettings = MemorySettings(),
    val toolSettings: ToolSettings = ToolSettings(),
) {
    init {
        require(displayName.isNotBlank()) { "User profile display name must not be blank" }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "User profile id must not be blank" }
        }
    }

    /**
     * User-owned AI settings: available connections, configured models, and the
     * default runtime choice.
     */
    @Serializable
    data class AiSettings(
        val defaultSelection: AiRuntimeSelection = UserProfileAiDefaults.defaultSelection,
        val connections: List<AiConnection> = UserProfileAiDefaults.connections(),
        val modelConfigurations: List<AiModelConfiguration> = UserProfileAiDefaults.modelConfigurations(),
    ) {
        init {
            require(connections.map { it.id }.distinct().size == connections.size) { "AI connection ids must be unique" }
            require(modelConfigurations.map { it.id }.distinct().size == modelConfigurations.size) {
                "AI model configuration ids must be unique"
            }
            require(modelConfigurations.all { configuration -> connections.any { it.id == configuration.connectionId } }) {
                "Every AI model configuration must reference an existing connection"
            }
            require(modelConfigurations.any { it.id == defaultSelection.modelConfigurationId }) {
                "Default AI runtime selection must reference an existing model configuration"
            }
        }
    }

    @Serializable
    data class SpeechSettings(
        val speechToText: SpeechToText = SpeechToText(),
        val textToSpeech: TextToSpeech = TextToSpeech(),
    ) {
        @Serializable
        data class SpeechToText(
            val enabled: Boolean = false,
            val mainLanguageCode: String = "en",
            val modelConfigurationId: AiModelConfiguration.Id = AiModelConfiguration.Id("openai-api-gpt-4o-transcribe"),
        ) {
            init {
                require(mainLanguageCode.isNotBlank()) { "Speech-to-text main language code must not be blank" }
            }
        }

        @Serializable
        data class TextToSpeech(
            val enabled: Boolean = false,
            val modelConfigurationId: AiModelConfiguration.Id = AiModelConfiguration.Id("openai-api-gpt-4o-mini-tts"),
            val voice: String = "alloy",
            val speed: Float = 1.0f,
        ) {
            init {
                require(voice.isNotBlank()) { "Text-to-speech voice must not be blank" }
                require(speed > 0.0f) { "Text-to-speech speed must be positive" }
            }
        }
    }

    @Serializable
    data class AgentSettings(
        val includeCurrentTime: Boolean = true,
        val responseFormat: ResponseFormat = ResponseFormat.XML_INLINE,
        val autoApproveAllTools: Boolean = true,
    ) {
        @Serializable
        enum class ResponseFormat {
            JSON,
            XML_STRUCTURED,
            XML_INLINE,
            PLAIN_TEXT,
        }
    }

    @Serializable
    data class MemorySettings(
        val autoRemember: Boolean = false,
        val autoRecall: Boolean = false,
    )

    @Serializable
    data class ToolSettings(
        val braveSearch: BraveSearch = BraveSearch(),
        val jinaReader: JinaReader = JinaReader(),
    ) {
        @Serializable
        data class BraveSearch(
            val enabled: Boolean = false,
            val apiKey: SecretRef? = null,
        )

        @Serializable
        data class JinaReader(
            val enabled: Boolean = false,
            val apiKey: SecretRef? = null,
        )
    }
}
