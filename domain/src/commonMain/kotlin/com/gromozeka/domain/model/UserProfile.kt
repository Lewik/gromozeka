package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
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
     * User-owned AI settings: available connections, configured models, and
     * purpose-specific runtime assignments.
     */
    @Serializable
    data class AiSettings(
        val connections: List<AiConnection> = UserProfileAiDefaults.connections(),
        val modelSpecs: List<AiModelSpec> = UserProfileAiDefaults.modelSpecs(),
        val modelConfigurations: List<AiModelConfiguration> = UserProfileAiDefaults.modelConfigurations(),
        val runtimeAssignments: List<AiRuntimeAssignment> = UserProfileAiDefaults.runtimeAssignments(),
    ) {
        init {
            require(connections.map { it.id }.distinct().size == connections.size) { "AI connection ids must be unique" }
            require(modelSpecs.map { it.provider to it.id }.distinct().size == modelSpecs.size) {
                "AI model specs must be unique by provider and model id"
            }
            require(modelConfigurations.map { it.id }.distinct().size == modelConfigurations.size) {
                "AI model configuration ids must be unique"
            }
            require(modelConfigurations.all { configuration -> connections.any { it.id == configuration.connectionId } }) {
                "Every AI model configuration must reference an existing connection"
            }
            require(runtimeAssignments.map { it.purpose }.distinct().size == runtimeAssignments.size) {
                "AI runtime assignment purposes must be unique"
            }
            val assignedPurposes = runtimeAssignments.map { it.purpose }
            val requiredPurposes = AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }
            require(assignedPurposes.containsAll(requiredPurposes)) {
                "Every primary AI runtime purpose must have an assignment"
            }
            modelConfigurations.filter { it.enabled }.forEach { configuration ->
                require(connectionFor(configuration)?.enabled == true) {
                    "Enabled AI model configuration ${configuration.id.value} must reference an enabled connection"
                }
                require(modelSpecFor(configuration) != null) {
                    "Enabled AI model configuration ${configuration.id.value} must have a model spec for " +
                        "${configuration.providerModelId}"
                }
            }
            runtimeAssignments.forEach { assignment ->
                val configuration = modelConfigurations.firstOrNull {
                    it.id == assignment.selection.modelConfigurationId
                } ?: error(
                    "AI runtime assignment ${assignment.purpose.name} references missing model configuration " +
                        assignment.selection.modelConfigurationId.value
                )
                require(configuration.enabled) {
                    "AI runtime assignment ${assignment.purpose.name} references disabled model configuration " +
                        configuration.id.value
                }
                val spec = modelSpecFor(configuration) ?: error(
                    "AI runtime assignment ${assignment.purpose.name} references model configuration " +
                        "${configuration.id.value} without a matching model spec"
                )
                require(spec.capabilities.containsAll(assignment.purpose.requiredCapabilities)) {
                    "AI runtime assignment ${assignment.purpose.name} requires capabilities " +
                        "${assignment.purpose.requiredCapabilities}, but ${configuration.id.value} has ${spec.capabilities}"
                }
            }
        }

        fun connectionFor(configuration: AiModelConfiguration): AiConnection? =
            connections.firstOrNull { it.id == configuration.connectionId }

        fun modelSpecFor(configuration: AiModelConfiguration): AiModelSpec? =
            connectionFor(configuration)?.let { connection ->
                modelSpecs.firstOrNull {
                    it.provider == connection.kind.provider && it.id == configuration.providerModelId
                }
            }

        fun runtimeSelectionFor(purpose: AiRuntimeAssignment.Purpose): AiRuntimeSelection? {
            var currentPurpose: AiRuntimeAssignment.Purpose? = purpose
            while (currentPurpose != null) {
                val purposeToCheck = currentPurpose
                val selection = runtimeAssignments.firstOrNull {
                    it.purpose == purposeToCheck
                }?.selection
                if (selection != null) return selection
                currentPurpose = purposeToCheck.fallbackPurpose
            }
            return null
        }

        fun supportsPurpose(configuration: AiModelConfiguration, purpose: AiRuntimeAssignment.Purpose): Boolean {
            if (!configuration.enabled) return false
            val connection = connectionFor(configuration) ?: return false
            if (!connection.enabled) return false
            val spec = modelSpecFor(configuration) ?: return false
            return spec.capabilities.containsAll(purpose.requiredCapabilities)
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
            val engine: Engine = Engine.OPENAI_API,
            val mainLanguageCode: String = "en",
            val localWhisper: LocalWhisper = LocalWhisper(),
        ) {
            init {
                require(mainLanguageCode.isNotBlank()) { "Speech-to-text main language code must not be blank" }
            }

            @Serializable
            enum class Engine {
                OPENAI_API,
                LOCAL_WHISPER,
            }

            @Serializable
            data class LocalWhisper(
                val executablePath: String = "whisper-cli",
                val modelName: String = "base",
                val modelPath: String = "",
                val timeoutSeconds: Int = 120,
                val serverStartupTimeoutSeconds: Int = 300,
                val audioContext: AudioContext = AudioContext(),
                val liveStreaming: LiveStreaming = LiveStreaming(),
            ) {
                init {
                    require(executablePath.isNotBlank()) { "Local Whisper executable path must not be blank" }
                    require(modelName.isNotBlank()) { "Local Whisper model name must not be blank" }
                    require(timeoutSeconds > 0) { "Local Whisper timeout must be positive" }
                    require(serverStartupTimeoutSeconds > 0) { "Local Whisper server startup timeout must be positive" }
                }

                fun audioContextFramesForWavBytes(audioBytes: Int): Int? =
                    audioContext.framesForWavBytes(audioBytes)

                @Serializable
                data class AudioContext(
                    val enabled: Boolean = true,
                    val minimumFrames: Int = 256,
                    val maximumFrames: Int = 1_500,
                    val paddingFrames: Int = 128,
                ) {
                    init {
                        require(minimumFrames > 0) { "Local Whisper audio context minimum must be positive" }
                        require(maximumFrames >= minimumFrames) {
                            "Local Whisper audio context maximum must be greater than or equal to minimum"
                        }
                        require(paddingFrames >= 0) { "Local Whisper audio context padding must not be negative" }
                    }

                    fun framesForWavBytes(audioBytes: Int): Int? {
                        if (!enabled) return null
                        val durationSeconds = (audioBytes - WavHeaderBytes).coerceAtLeast(0) / WavBytesPerSecond
                        val frames = (durationSeconds / WhisperWindowSeconds * maximumFrames).toInt() + paddingFrames
                        return frames.coerceIn(minimumFrames, maximumFrames)
                    }

                    private companion object {
                        private const val WavHeaderBytes = 44
                        private const val WavBytesPerSecond = 32_000.0
                        private const val WhisperWindowSeconds = 30.0
                    }
                }

                @Serializable
                data class LiveStreaming(
                    val profile: Profile = Profile.BALANCED,
                    val maximumAdaptiveWindowMillis: Long = 90_000L,
                ) {
                    init {
                        require(maximumAdaptiveWindowMillis >= profile.windowMillis) {
                            "Local Whisper maximum adaptive live window must be greater than or equal to profile window"
                        }
                    }

                    val windowMillis: Long get() = profile.windowMillis
                    val stepMillis: Long get() = profile.stepMillis
                    val minWindowMillis: Long get() = profile.minWindowMillis
                    val overlapMillis: Long get() = profile.overlapMillis

                    @Serializable
                    enum class Profile(
                        val label: String,
                        val windowMillis: Long,
                        val stepMillis: Long,
                        val minWindowMillis: Long,
                        val overlapMillis: Long,
                    ) {
                        LOW_LATENCY("Low latency", 12_000L, 6_000L, 4_000L, 4_000L),
                        BALANCED("Balanced", 20_000L, 15_000L, 5_000L, 5_000L),
                        SLOW_CPU("Slow CPU", 30_000L, 25_000L, 8_000L, 5_000L),
                    }
                }
            }
        }

        @Serializable
        data class TextToSpeech(
            val enabled: Boolean = false,
            val voice: String = "marin",
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
        val autoApproveAllTools: Boolean = true,
    )

    @Serializable
    data class MemorySettings(
        val autoRemember: Boolean = false,
        val autoRecall: Boolean = false,
        val defaultNamespace: String? = null,
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
