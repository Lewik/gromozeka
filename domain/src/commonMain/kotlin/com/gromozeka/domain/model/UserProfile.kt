package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
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
    val speechSettings: SpeechSettings = SpeechSettings(),
    val agentSettings: AgentSettings = AgentSettings(),
    val memorySettings: MemorySettings = MemorySettings(),
    val messageInstructionGroups: List<MessageInstructionGroup> = MessageInstructionGroup.defaults(),
    val messageInstructionTextShortcuts: MessageInstructionTextShortcutSettings =
        MessageInstructionTextShortcutSettings(),
    val quickTextActions: List<QuickTextAction> = QuickTextAction.defaults(),
) {
    init {
        require(displayName.isNotBlank()) { "User profile display name must not be blank" }
        require(messageInstructionGroups.map { it.id }.distinct().size == messageInstructionGroups.size) {
            "Message instruction group ids must be unique"
        }
        require(quickTextActions.isNotEmpty()) { "User profile must contain at least one quick text action" }
        require(quickTextActions.map { it.id }.distinct().size == quickTextActions.size) {
            "Quick text action ids must be unique"
        }
        val instructionIds = messageInstructionGroups.flatMap { group -> group.controls.map { it.data.id } }
        require(instructionIds.distinct().size == instructionIds.size) {
            "Message instruction ids must be unique across groups"
        }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "User profile id must not be blank" }
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
            val claudeCodeConnectionId: AiConnection.Id? = null,
            val audioSource: SpeechAudioSource = SpeechAudioSource.CurrentClient,
            val localWhisper: LocalWhisper = LocalWhisper(),
        ) {
            init {
                require(mainLanguageCode.isNotBlank()) { "Speech-to-text main language code must not be blank" }
            }

            @Serializable
            enum class Engine {
                OPENAI_API,
                LOCAL_WHISPER,
                CLAUDE_CODE,
            }

            @Serializable
            data class LocalWhisper(
                val executablePath: String = "whisper-cli",
                val modelName: String = "base",
                val modelPath: String = "",
                val threadCount: Int = 0,
                val extraArguments: List<String> = emptyList(),
                val timeoutSeconds: Int = 120,
                val serverStartupTimeoutSeconds: Int = 300,
                val audioContext: AudioContext = AudioContext(),
                val liveStreaming: LiveStreaming = LiveStreaming(),
                val executionTarget: AiExecutionTarget = AiExecutionTarget.Server,
            ) {
                init {
                    require(executablePath.isNotBlank()) { "Local Whisper executable path must not be blank" }
                    require(modelName.isNotBlank()) { "Local Whisper model name must not be blank" }
                    require(threadCount >= 0) { "Local Whisper thread count must not be negative" }
                    require(extraArguments.all { it.isNotBlank() }) {
                        "Local Whisper extra arguments must not contain blank values"
                    }
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
        val includeMessageTemporalContext: Boolean = true,
        val autoApproveAllTools: Boolean = true,
    )

    @Serializable
    data class MemorySettings(
        val autoRemember: Boolean = false,
        val autoRecall: Boolean = false,
        val forceWriteForDocumentIngest: Boolean = true,
    )

}
