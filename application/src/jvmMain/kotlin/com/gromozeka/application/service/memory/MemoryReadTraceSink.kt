package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryRun
import kotlinx.datetime.Instant

interface MemoryReadTraceSink {
    fun onMemoryRead(event: MemoryReadTraceEvent)
}

data class MemoryReadTraceEvent(
    val namespace: MemoryNamespace,
    val conversationId: Conversation.Id,
    val threadId: Conversation.Thread.Id,
    val targetMessageId: Conversation.Message.Id,
    val result: MemoryReadResult,
    val startedAt: Instant,
    val completedAt: Instant,
    val latencyMs: Long,
    val llmCalls: List<MemoryRun.LlmCallTiming>,
)
