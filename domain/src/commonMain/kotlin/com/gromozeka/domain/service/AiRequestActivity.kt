package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import com.gromozeka.statesync.StateSyncService

const val AI_ADMISSION_CONVERSATION_CONTEXT_KEY = "runtimeConversationId"
const val AI_ADMISSION_TASK_CONTEXT_KEY = "runtimeTaskId"

@Serializable
data class AiAdmissionOrigin(
    val conversationId: Conversation.Id,
    val taskId: ConversationRuntimeTask.Id,
) {
    companion object {
        fun fromContext(context: Map<String, Any?>): AiAdmissionOrigin? {
            val rawTask = context[AI_ADMISSION_TASK_CONTEXT_KEY]
            val rawConversation = context[AI_ADMISSION_CONVERSATION_CONTEXT_KEY]
            if (rawTask == null && rawConversation == null) return null
            val task = requireNotNull(rawTask as? String) { "AI admission task id must be a string" }.also { require(it.isNotBlank()) }
            val conversation = requireNotNull(rawConversation as? String) { "AI admission conversation id must be a string" }.also { require(it.isNotBlank()) }
            return AiAdmissionOrigin(Conversation.Id(conversation), ConversationRuntimeTask.Id(task))
        }
    }
}

@Serializable
enum class AiRequestWaitReason { TPM, CONCURRENCY }

@Serializable
data class AiRequestTaskWait(
    val taskId: ConversationRuntimeTask.Id,
    val reason: AiRequestWaitReason,
)

/** Aggregate Server load and only the waits belonging to the authorized conversation. */
@Serializable
data class AiRequestActivitySnapshot(
    val runningRequests: Int,
    val waitingForTpm: Int,
    val waitingForConcurrency: Int,
    val taskWaits: List<AiRequestTaskWait>,
) {
    init {
        require(runningRequests >= 0 && waitingForTpm >= 0 && waitingForConcurrency >= 0)
        require(taskWaits.distinct().size == taskWaits.size)
    }
}

interface AiRequestActivityService {
    fun observe(conversationId: Conversation.Id): Flow<AiRequestActivitySnapshot>
}

interface AiRequestActivityStateSyncService : StateSyncService<Conversation.Id, AiRequestActivitySnapshot>
