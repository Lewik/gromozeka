package com.gromozeka.domain.model.ai

import kotlinx.serialization.Serializable

/**
 * User choice of a concrete model configuration for one Gromozeka runtime purpose.
 */
@Serializable
data class AiRuntimeAssignment(
    val purpose: Purpose,
    val selection: AiRuntimeSelection,
) {
    @Serializable
    enum class Purpose(
        val requiredCapabilities: Set<AiModelCapability>,
        val displayName: String,
        val description: String,
    ) {
        DEFAULT_CHAT(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Default chat",
            description = "Default model for newly created agents and normal chat unless an agent overrides it.",
        ),
        MESSAGE_SQUASH(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Message squash",
            description = "Model used to summarize or compact chat messages.",
        ),
        MEMORY_READ(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory read",
            description = "Planner and selector used before the main model call.",
        ),
        MEMORY_WRITE(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write",
            description = "Router, extractor, reconciler, and materializer used when writing memory.",
        ),
        MEMORY_MAINTENANCE(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory maintenance",
            description = "Consolidation, repair, entity maintenance, and retention jobs.",
        ),
        LIVE_TRANSCRIPT_STABILIZER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Live transcript stabilizer",
            description = "Model that turns noisy overlapping ASR drafts into stable original transcript.",
        ),
        LIVE_TRANSLATION(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Live translation",
            description = "Model that translates stabilized live transcript deltas.",
        ),
        SPEECH_TO_TEXT(
            requiredCapabilities = setOf(AiModelCapability.SPEECH_TO_TEXT),
            displayName = "Speech-to-text",
            description = "Remote speech recognition model used when STT engine is OpenAI API.",
        ),
        TEXT_TO_SPEECH(
            requiredCapabilities = setOf(AiModelCapability.TEXT_TO_SPEECH),
            displayName = "Text-to-speech",
            description = "Voice synthesis model.",
        ),

        ;
    }
}
