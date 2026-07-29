package com.gromozeka.server

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeExecutorDescriptor
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import com.gromozeka.shared.uuid.uuid7
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ConversationRuntimeServerConfiguration {
    @Bean
    fun conversationRuntimeExecutorDescriptor(): ConversationRuntimeExecutorDescriptor =
        ConversationRuntimeExecutorDescriptor(
            identity = ConversationRuntimeExecutorIdentity.Server(
                sessionId = ConversationRuntimeServerSessionId(uuid7()),
            ),
            capabilities = setOf(
                ConversationRuntimeCapability.CONVERSATION_TURN,
                ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                ConversationRuntimeCapability.TOOL_EXECUTION,
                ConversationRuntimeCapability.MEMORY_PIPELINE,
            ),
        )
}
