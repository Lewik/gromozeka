package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.model.memory.MemoryNamespace

interface MemoryWriteTraceSink {
    fun onMemoryWrite(event: MemoryWriteTraceEvent)
}

data class MemoryWriteTraceEvent(
    val namespace: MemoryNamespace,
    val conversationId: Conversation.Id,
    val threadId: Conversation.Thread.Id,
    val targetMessageId: Conversation.Message.Id,
    val result: DirectStructuredMemoryWriteResult,
)
