package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryReadResult

interface MemoryReadTraceSink {
    fun onMemoryRead(event: MemoryReadTraceEvent)
}

data class MemoryReadTraceEvent(
    val namespace: MemoryNamespace,
    val conversationId: Conversation.Id,
    val threadId: Conversation.Thread.Id,
    val targetMessageId: Conversation.Message.Id,
    val result: MemoryReadResult,
)
