package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.presentation.services.translation.data.Translation

internal fun AiReasoningMode.runtimeDisplayName(translation: Translation): String = translation.text(when (this) {
    AiReasoningMode.DISABLED -> "session.runtime.enum.reasoningMode.disabled"
    AiReasoningMode.ADAPTIVE -> "session.runtime.enum.reasoningMode.adaptive"
    AiReasoningMode.TOKEN_BUDGET -> "session.runtime.enum.reasoningMode.token_budget"
})

internal fun AiReasoningEffort.runtimeDisplayName(translation: Translation): String = translation.text(when (this) {
    AiReasoningEffort.LOW -> "session.runtime.enum.reasoningEffort.low"
    AiReasoningEffort.MEDIUM -> "session.runtime.enum.reasoningEffort.medium"
    AiReasoningEffort.HIGH -> "session.runtime.enum.reasoningEffort.high"
    AiReasoningEffort.XHIGH -> "session.runtime.enum.reasoningEffort.xhigh"
    AiReasoningEffort.MAX -> "session.runtime.enum.reasoningEffort.max"
})

internal fun AiReasoningDisplay.runtimeDisplayName(translation: Translation): String = translation.text(when (this) {
    AiReasoningDisplay.FULL -> "session.runtime.enum.thinkingDisplay.full"
    AiReasoningDisplay.SUMMARIZED -> "session.runtime.enum.thinkingDisplay.summarized"
    AiReasoningDisplay.OMITTED -> "session.runtime.enum.thinkingDisplay.omitted"
})

internal fun AiModelConfiguration.AssistantResponseFormat.runtimeDisplayName(translation: Translation): String = translation.text(when (this) {
    AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA -> "session.runtime.enum.responseFormat.json_schema"
    AiModelConfiguration.AssistantResponseFormat.XML_STRUCTURED -> "session.runtime.enum.responseFormat.xml_structured"
    AiModelConfiguration.AssistantResponseFormat.XML_INLINE -> "session.runtime.enum.responseFormat.xml_inline"
    AiModelConfiguration.AssistantResponseFormat.TEXT -> "session.runtime.enum.responseFormat.text"
})

internal fun ConversationRuntimeTraceEntry.Kind.runtimeDisplayName(translation: Translation): String =
    translation.text(when (this) {
        ConversationRuntimeTraceEntry.Kind.TASK_SUBMITTED -> "session.runtime.trace.task_submitted"
        ConversationRuntimeTraceEntry.Kind.TASK_CLAIMED -> "session.runtime.trace.task_claimed"
        ConversationRuntimeTraceEntry.Kind.TASK_STARTED -> "session.runtime.trace.task_started"
        ConversationRuntimeTraceEntry.Kind.TASK_IN_DOUBT -> "session.runtime.trace.task_in_doubt"
        ConversationRuntimeTraceEntry.Kind.TASK_COMPLETED -> "session.runtime.trace.task_completed"
        ConversationRuntimeTraceEntry.Kind.TASK_FAILED -> "session.runtime.trace.task_failed"
        ConversationRuntimeTraceEntry.Kind.TASK_CANCELLED -> "session.runtime.trace.task_cancelled"
        ConversationRuntimeTraceEntry.Kind.CONTROL_REQUESTED -> "session.runtime.trace.control_requested"
        ConversationRuntimeTraceEntry.Kind.COMMAND_TASK -> "session.runtime.trace.command_task"
        ConversationRuntimeTraceEntry.Kind.COMMAND_MONITOR -> "session.runtime.trace.command_monitor"
        ConversationRuntimeTraceEntry.Kind.EVENT_PUBLISHED -> "session.runtime.trace.event_published"
        ConversationRuntimeTraceEntry.Kind.TOOL_EXECUTION -> "runtime.toolExecutionTask"
    })

internal fun runtimeMemoryOperationLabel(operation: String, translation: Translation): String = when (operation) {
    "remember" -> translation.text("session.runtime.memoryOperation.remember")
    "forget_source" -> translation.text("session.runtime.memoryOperation.forget_source")
    "enrich_context" -> translation.text("session.runtime.memoryOperation.enrich_context")
    "answer_question" -> translation.text("session.runtime.memoryOperation.answer_question")
    "maintenance" -> translation.text("session.runtime.memoryOperation.maintenance")
    "route" -> translation.text("session.runtime.memoryOperation.route")
    "document_ingest" -> translation.text("session.runtime.memoryOperation.document_ingest")
    "retrieve_update" -> translation.text("session.runtime.memoryOperation.retrieve_update")
    "canonicalize" -> translation.text("session.runtime.memoryOperation.canonicalize")
    "construct_notes" -> translation.text("session.runtime.memoryOperation.construct_notes")
    "reconcile_notes" -> translation.text("session.runtime.memoryOperation.reconcile_notes")
    "extract_claims" -> translation.text("session.runtime.memoryOperation.extract_claims")
    "reconcile_claims" -> translation.text("session.runtime.memoryOperation.reconcile_claims")
    "update_profile" -> translation.text("session.runtime.memoryOperation.update_profile")
    "update_action_items" -> translation.text("session.runtime.memoryOperation.update_action_items")
    "consolidate_notes" -> translation.text("session.runtime.memoryOperation.consolidate_notes")
    "maintain_entities" -> translation.text("session.runtime.memoryOperation.maintain_entities")
    "repair_memory" -> translation.text("session.runtime.memoryOperation.repair_memory")
    "rebuild_embeddings" -> translation.text("session.runtime.memoryOperation.rebuild_embeddings")
    "forget_memory" -> translation.text("session.runtime.memoryOperation.forget_memory")
    "read_plan" -> translation.text("session.runtime.memoryOperation.read_plan")
    "compose_answer" -> translation.text("session.runtime.memoryOperation.compose_answer")
    "compact" -> translation.text("session.runtime.memoryOperation.compact")
    else -> operation
}

internal fun AiProvider.runtimeDisplayName(translation: Translation): String = translation.text(when (this) {
    AiProvider.OPENAI -> "ai.provider.openai"
    AiProvider.ANTHROPIC -> "ai.provider.anthropic"
    AiProvider.GOOGLE -> "ai.provider.google"
    AiProvider.OLLAMA -> "ai.provider.ollama"
    AiProvider.CUSTOM -> "ai.provider.custom"
})

internal fun runtimeProviderLabel(provider: String, translation: Translation): String =
    AiProvider.entries.firstOrNull { it.name.equals(provider, ignoreCase = true) }
        ?.runtimeDisplayName(translation) ?: provider
