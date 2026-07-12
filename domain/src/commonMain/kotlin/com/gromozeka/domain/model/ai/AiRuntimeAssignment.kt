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
        MEMORY_READ_CONTEXT_COMPACTOR(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory read / Context compactor",
            description = "Optional override for compacting large conversation context before memory recall.",
        ),
        MEMORY_READ_PLANNER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory read / Planner",
            description = "Optional override for deciding whether recall is needed and what to retrieve.",
        ),
        MEMORY_READ_SELECTOR(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory read / Selector",
            description = "Optional override for selecting recalled candidates before prompt injection.",
        ),
        MEMORY_READ_ANSWER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory read / Answer",
            description = "Optional override for answering direct questions from selected memory context.",
        ),
        MEMORY_WRITE(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write",
            description = "Router, extractor, reconciler, and materializer used when writing memory.",
        ),
        MEMORY_WRITE_CONTEXT_COMPACTOR(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write / Context compactor",
            description = "Optional override for compacting large conversation context before memory writing.",
        ),
        MEMORY_WRITE_INGEST_PLANNER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write / Ingest planner",
            description = "Optional override for validating and segmenting explicit memory inputs before writing.",
        ),
        MEMORY_WRITE_ROUTER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write / Router",
            description = "Optional override for routing a source to memory types or no-op.",
        ),
        MEMORY_WRITE_RETRIEVAL_PLANNER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write / Retrieval planner",
            description = "Optional override for choosing existing memories to retrieve before write reconciliation.",
        ),
        MEMORY_WRITE_ENTITY_CANONICALIZER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write / Entity canonicalizer",
            description = "Optional override for reusing or creating canonical memory entities.",
        ),
        MEMORY_WRITE_NOTE_CONSTRUCTOR(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write / Note constructor",
            description = "Optional override for extracting note candidates from a source.",
        ),
        MEMORY_WRITE_NOTE_RECONCILER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write / Note reconciler",
            description = "Optional override for deduplicating and updating existing notes.",
        ),
        MEMORY_WRITE_CLAIM_EXTRACTOR(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write / Claim extractor",
            description = "Optional override for extracting structured factual claims.",
        ),
        MEMORY_WRITE_CLAIM_RECONCILER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write / Claim reconciler",
            description = "Optional override for claim deduplication, contradiction handling, and superseding.",
        ),
        MEMORY_WRITE_ACTION_ITEM_UPDATER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write / Action item updater",
            description = "Optional override for creating, updating, and closing memory action items.",
        ),
        MEMORY_WRITE_FORGET_PLANNER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory write / Forget planner",
            description = "Optional override for explicit forget decisions triggered by memory write routing.",
        ),
        MEMORY_MAINTENANCE(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory maintenance",
            description = "Consolidation, repair, entity maintenance, and retention jobs.",
        ),
        MEMORY_MAINTENANCE_NOTE_CONSOLIDATOR(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory maintenance / Note consolidator",
            description = "Optional override for periodic note consolidation.",
        ),
        MEMORY_MAINTENANCE_REPAIR_PLANNER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory maintenance / Repair planner",
            description = "Optional override for detecting and repairing suspicious memory records.",
        ),
        MEMORY_MAINTENANCE_ENTITY_PLANNER(
            requiredCapabilities = setOf(AiModelCapability.TEXT_GENERATION),
            displayName = "Memory maintenance / Entity planner",
            description = "Optional override for merging and repairing entity identities.",
        ),
        MEMORY_EMBEDDINGS(
            requiredCapabilities = setOf(AiModelCapability.EMBEDDINGS),
            displayName = "Memory embeddings",
            description = "Embedding model used for vector indexes and semantic memory retrieval.",
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

        val fallbackPurpose: Purpose?
            get() = when (this) {
                MEMORY_READ_CONTEXT_COMPACTOR,
                MEMORY_READ_PLANNER,
                MEMORY_READ_SELECTOR,
                MEMORY_READ_ANSWER -> MEMORY_READ

                MEMORY_WRITE_CONTEXT_COMPACTOR,
                MEMORY_WRITE_INGEST_PLANNER,
                MEMORY_WRITE_ROUTER,
                MEMORY_WRITE_RETRIEVAL_PLANNER,
                MEMORY_WRITE_ENTITY_CANONICALIZER,
                MEMORY_WRITE_NOTE_CONSTRUCTOR,
                MEMORY_WRITE_NOTE_RECONCILER,
                MEMORY_WRITE_CLAIM_EXTRACTOR,
                MEMORY_WRITE_CLAIM_RECONCILER,
                MEMORY_WRITE_ACTION_ITEM_UPDATER,
                MEMORY_WRITE_FORGET_PLANNER -> MEMORY_WRITE

                MEMORY_MAINTENANCE_NOTE_CONSOLIDATOR,
                MEMORY_MAINTENANCE_REPAIR_PLANNER,
                MEMORY_MAINTENANCE_ENTITY_PLANNER -> MEMORY_MAINTENANCE

                else -> null
            }

        val requiresExplicitAssignment: Boolean
            get() = fallbackPurpose == null
    }
}
