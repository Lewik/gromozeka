package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import kotlinx.datetime.Instant

interface MemoryWriteTraceSink {
    fun onMemoryWrite(event: MemoryWriteTraceEvent)
}

data class MemoryWriteTraceEvent(
    val namespace: MemoryNamespace,
    val conversationId: Conversation.Id,
    val threadId: Conversation.Thread.Id,
    val targetMessageId: Conversation.Message.Id,
    val result: DirectStructuredMemoryWriteResult,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val latencyMs: Long? = null,
    val llmCalls: List<MemoryRun.LlmCallTiming> = emptyList(),
)
