package com.gromozeka.presentation.ui

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.presentation.services.translation.data.Translation

internal fun AiProvider.aiLabel(translation: Translation): String =
    translation.text(
        when (this) {
            AiProvider.ANTHROPIC -> "ai.provider.anthropic"
            AiProvider.CUSTOM -> "ai.provider.custom"
            AiProvider.GOOGLE -> "ai.provider.google"
            AiProvider.OLLAMA -> "ai.provider.ollama"
            AiProvider.OPENAI -> "ai.provider.openai"
        }
    )

internal fun AiConnection.Kind.aiLabel(translation: Translation): String =
    translation.text(
        when (this) {
            AiConnection.Kind.ANTHROPIC_API -> "ai.connection.kind.anthropic_api"
            AiConnection.Kind.ANTHROPIC_BEDROCK -> "ai.connection.kind.anthropic_bedrock"
            AiConnection.Kind.CLAUDE_CODE -> "ai.connection.kind.claude_code"
            AiConnection.Kind.GEMINI_API -> "ai.connection.kind.gemini_api"
            AiConnection.Kind.GITHUB_COPILOT -> "ai.connection.kind.github_copilot"
            AiConnection.Kind.OLLAMA -> "ai.connection.kind.ollama"
            AiConnection.Kind.OPENAI_API -> "ai.connection.kind.openai_api"
            AiConnection.Kind.OPENAI_COMPATIBLE -> "ai.connection.kind.openai_compatible"
            AiConnection.Kind.OPENAI_SUBSCRIPTION -> "ai.connection.kind.openai_subscription"
        }
    )

internal fun AiModelCapability.aiLabel(translation: Translation): String =
    translation.text(
        when (this) {
            AiModelCapability.DOCUMENT_INPUT -> "ai.capability.document_input"
            AiModelCapability.EMBEDDINGS -> "ai.capability.embeddings"
            AiModelCapability.IMAGE_INPUT -> "ai.capability.image_input"
            AiModelCapability.SPEECH_TO_TEXT -> "ai.capability.speech_to_text"
            AiModelCapability.STRUCTURED_OUTPUT -> "ai.capability.structured_output"
            AiModelCapability.TEXT_GENERATION -> "ai.capability.text_generation"
            AiModelCapability.TEXT_TO_SPEECH -> "ai.capability.text_to_speech"
            AiModelCapability.TOOL_CALLING -> "ai.capability.tool_calling"
        }
    )

internal fun AiReasoningMode.aiLabel(translation: Translation): String =
    translation.text(
        when (this) {
            AiReasoningMode.ADAPTIVE -> "ai.reasoning.mode.adaptive"
            AiReasoningMode.DISABLED -> "ai.reasoning.mode.disabled"
            AiReasoningMode.TOKEN_BUDGET -> "ai.reasoning.mode.token_budget"
        }
    )

internal fun AiReasoningEffort.aiLabel(translation: Translation): String =
    translation.text(
        when (this) {
            AiReasoningEffort.HIGH -> "ai.reasoning.effort.high"
            AiReasoningEffort.LOW -> "ai.reasoning.effort.low"
            AiReasoningEffort.MAX -> "ai.reasoning.effort.max"
            AiReasoningEffort.MEDIUM -> "ai.reasoning.effort.medium"
            AiReasoningEffort.XHIGH -> "ai.reasoning.effort.xhigh"
        }
    )

internal fun AiReasoningDisplay.aiLabel(translation: Translation): String =
    translation.text(
        when (this) {
            AiReasoningDisplay.FULL -> "ai.reasoning.display.full"
            AiReasoningDisplay.OMITTED -> "ai.reasoning.display.omitted"
            AiReasoningDisplay.SUMMARIZED -> "ai.reasoning.display.summarized"
        }
    )

internal fun AiModelConfiguration.AssistantResponseFormat.aiLabel(translation: Translation): String =
    translation.text(
        when (this) {
            AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA -> "ai.response_format.json_schema"
            AiModelConfiguration.AssistantResponseFormat.TEXT -> "ai.response_format.text"
            AiModelConfiguration.AssistantResponseFormat.XML_INLINE -> "ai.response_format.xml_inline"
            AiModelConfiguration.AssistantResponseFormat.XML_STRUCTURED -> "ai.response_format.xml_structured"
        }
    )

internal fun WorkerCatalogEntry.Status.aiLabel(translation: Translation): String =
    translation.text(
        when (this) {
            WorkerCatalogEntry.Status.OFFLINE -> "ai.worker.status.offline"
            WorkerCatalogEntry.Status.ONLINE -> "ai.worker.status.online"
        }
    )

internal fun AgentSkill.MaterializationPlan.Policy.aiLabel(translation: Translation): String =
    translation.text(
        when (this) {
            AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED -> "agents.skills.policy.not_required"
            AgentSkill.MaterializationPlan.Policy.REQUIRED -> "agents.skills.policy.required"
        }
    )

internal fun AiRuntimeAssignment.Purpose.aiLabel(translation: Translation): String =
    translation.text("$aiTranslationKey.label")

internal fun AiRuntimeAssignment.Purpose.aiDescription(translation: Translation): String =
    translation.text("$aiTranslationKey.description")

private val AiRuntimeAssignment.Purpose.aiTranslationKey: String
    get() = when (this) {
        AiRuntimeAssignment.Purpose.AGENT_SKILL_ANALYSIS -> "ai.runtime.purpose.agent_skill_analysis"
        AiRuntimeAssignment.Purpose.DEFAULT_CHAT -> "ai.runtime.purpose.default_chat"
        AiRuntimeAssignment.Purpose.LIVE_TRANSCRIPT_STABILIZER -> "ai.runtime.purpose.live_transcript_stabilizer"
        AiRuntimeAssignment.Purpose.LIVE_TRANSLATION -> "ai.runtime.purpose.live_translation"
        AiRuntimeAssignment.Purpose.MEMORY_EMBEDDINGS -> "ai.runtime.purpose.memory_embeddings"
        AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE -> "ai.runtime.purpose.memory_maintenance"
        AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE_ENTITY_PLANNER -> "ai.runtime.purpose.memory_maintenance_entity_planner"
        AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE_NOTE_CONSOLIDATOR -> "ai.runtime.purpose.memory_maintenance_note_consolidator"
        AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE_REPAIR_PLANNER -> "ai.runtime.purpose.memory_maintenance_repair_planner"
        AiRuntimeAssignment.Purpose.MEMORY_READ -> "ai.runtime.purpose.memory_read"
        AiRuntimeAssignment.Purpose.MEMORY_READ_ANSWER -> "ai.runtime.purpose.memory_read_answer"
        AiRuntimeAssignment.Purpose.MEMORY_READ_CONTEXT_COMPACTOR -> "ai.runtime.purpose.memory_read_context_compactor"
        AiRuntimeAssignment.Purpose.MEMORY_READ_PLANNER -> "ai.runtime.purpose.memory_read_planner"
        AiRuntimeAssignment.Purpose.MEMORY_READ_SELECTOR -> "ai.runtime.purpose.memory_read_selector"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE -> "ai.runtime.purpose.memory_write"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_ACTION_ITEM_UPDATER -> "ai.runtime.purpose.memory_write_action_item_updater"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_CLAIM_EXTRACTOR -> "ai.runtime.purpose.memory_write_claim_extractor"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_CLAIM_RECONCILER -> "ai.runtime.purpose.memory_write_claim_reconciler"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_CONTEXT_COMPACTOR -> "ai.runtime.purpose.memory_write_context_compactor"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_ENTITY_CANONICALIZER -> "ai.runtime.purpose.memory_write_entity_canonicalizer"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_FORGET_PLANNER -> "ai.runtime.purpose.memory_write_forget_planner"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_INGEST_PLANNER -> "ai.runtime.purpose.memory_write_ingest_planner"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_NOTE_CONSTRUCTOR -> "ai.runtime.purpose.memory_write_note_constructor"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_NOTE_RECONCILER -> "ai.runtime.purpose.memory_write_note_reconciler"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_RETRIEVAL_PLANNER -> "ai.runtime.purpose.memory_write_retrieval_planner"
        AiRuntimeAssignment.Purpose.MEMORY_WRITE_ROUTER -> "ai.runtime.purpose.memory_write_router"
        AiRuntimeAssignment.Purpose.MESSAGE_SQUASH -> "ai.runtime.purpose.message_squash"
        AiRuntimeAssignment.Purpose.QUICK_TEXT_ACTION -> "ai.runtime.purpose.quick_text_action"
        AiRuntimeAssignment.Purpose.SPEECH_TO_TEXT -> "ai.runtime.purpose.speech_to_text"
        AiRuntimeAssignment.Purpose.SUGGESTED_REPLIES -> "ai.runtime.purpose.suggested_replies"
        AiRuntimeAssignment.Purpose.TEXT_TO_SPEECH -> "ai.runtime.purpose.text_to_speech"
        AiRuntimeAssignment.Purpose.TOOL_CATALOG_SUMMARY -> "ai.runtime.purpose.tool_catalog_summary"
    }
