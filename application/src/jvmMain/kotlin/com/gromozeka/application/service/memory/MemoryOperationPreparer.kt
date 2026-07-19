package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.service.ConversationDomainService
import java.security.MessageDigest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service

@Service
class MemoryOperationPreparer(
    private val conversationService: ConversationDomainService,
) {
    private val sourceMapper = ConversationMessageMemorySourceMapper()

    suspend fun prepareRememberThread(
        conversationIdValue: String,
        namespaceValue: String? = null,
    ): List<PreparedMemoryOperation> {
        val conversationId = Conversation.Id(conversationIdValue)
        val conversation = requireConversation(conversationId)
        val namespace = resolveNamespace(namespaceValue)
        return conversationService.loadCurrentMessages(conversationId).mapNotNull { message ->
            if (message.isSyntheticMemoryMessage()) {
                return@mapNotNull null
            }
            val source = sourceMapper.toChatTurn(
                namespace = namespace,
                conversationId = conversationId,
                threadId = conversation.currentThread,
                message = message,
            ) ?: return@mapNotNull null
            PreparedMemoryOperation(
                request = MemoryOperationRequest.RememberMessage(
                    namespace = namespace,
                    conversationId = conversationId,
                    threadId = conversation.currentThread,
                    targetMessageId = message.id,
                    forceWrite = null,
                    confirmedPreflightRunId = null,
                ),
                summary = "Memory remember queued",
                inputHash = source.contentHash,
            )
        }
    }

    suspend fun prepareRememberMessage(
        conversationIdValue: String,
        targetMessageId: String? = null,
        forceWrite: Boolean? = null,
        confirmedPreflightRunId: String? = null,
        namespaceValue: String? = null,
    ): PreparedMemoryOperation {
        val conversationId = Conversation.Id(conversationIdValue)
        val conversation = requireConversation(conversationId)
        val targetMessage = resolveTargetMessage(
            conversationService.loadCurrentMessages(conversationId),
            targetMessageId,
        )
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.RememberMessage(
                namespace = resolveNamespace(namespaceValue),
                conversationId = conversationId,
                threadId = conversation.currentThread,
                targetMessageId = targetMessage.id,
                forceWrite = forceWrite,
                confirmedPreflightRunId = confirmedPreflightRunId.toMemoryRunIdOrNull(),
            ),
            summary = "Memory remember queued",
            inputHash = targetMessage.id.value.sha256(),
        )
    }

    suspend fun prepareRememberProvidedContent(
        conversationIdValue: String?,
        text: String? = null,
        filePath: String? = null,
        rawUrl: String? = null,
        documentType: String? = null,
        title: String? = null,
        sourceRef: String? = null,
        forceWrite: Boolean? = null,
        confirmedPreflightRunId: String? = null,
        mode: String? = null,
        namespaceValue: String? = null,
        writeSurface: MemoryWriteSurface = MemoryWriteSurface.CHAT_TOOL,
    ): PreparedMemoryOperation {
        val content = MemoryRememberContentRequest.fromExternal(
            text = text,
            filePath = filePath,
            rawUrl = rawUrl,
            documentType = documentType,
            title = title,
            sourceRef = sourceRef,
        )
        val conversation = conversationIdValue.toConversationIdOrNull()?.let { requireConversation(it) }
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.RememberProvidedContent(
                namespace = resolveNamespace(namespaceValue),
                conversationId = conversation?.id,
                threadId = conversation?.currentThread,
                content = content,
                forceWrite = forceWrite,
                confirmedPreflightRunId = confirmedPreflightRunId.toMemoryRunIdOrNull(),
                mode = mode,
                writeSurface = writeSurface,
            ),
            summary = if (content.documentType == null) "Memory remember queued" else "Document ingest queued",
            inputHash = content.input.identityValue().sha256(),
        )
    }

    suspend fun prepareEnrichMessage(
        conversationIdValue: String,
        targetMessageId: String? = null,
        namespaceValue: String? = null,
    ): PreparedMemoryOperation {
        val conversationId = Conversation.Id(conversationIdValue)
        val conversation = requireConversation(conversationId)
        val targetMessage = resolveTargetMessage(
            conversationService.loadCurrentMessages(conversationId),
            targetMessageId,
        )
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.EnrichMessage(
                namespace = resolveNamespace(namespaceValue),
                conversationId = conversationId,
                threadId = conversation.currentThread,
                targetMessageId = targetMessage.id,
            ),
            summary = "Memory context enrichment queued",
            inputHash = targetMessage.id.value.sha256(),
        )
    }

    suspend fun prepareEnrichProvidedContext(
        conversationIdValue: String?,
        contextText: String,
        mode: String? = null,
        namespaceValue: String? = null,
    ): PreparedMemoryOperation {
        val normalizedContext = contextText.trim()
        require(normalizedContext.isNotBlank()) { "Provided context is blank." }
        val conversation = conversationIdValue.toConversationIdOrNull()?.let { requireConversation(it) }
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.EnrichProvidedContext(
                namespace = resolveNamespace(namespaceValue),
                conversationId = conversation?.id,
                threadId = conversation?.currentThread,
                context = normalizedContext,
                mode = mode,
            ),
            summary = "Memory context enrichment queued",
            inputHash = normalizedContext.sha256(),
        )
    }

    suspend fun prepareAnswerMessage(
        conversationIdValue: String,
        targetMessageId: String? = null,
        namespaceValue: String? = null,
    ): PreparedMemoryOperation {
        val conversationId = Conversation.Id(conversationIdValue)
        val conversation = requireConversation(conversationId)
        val targetMessage = resolveTargetMessage(
            conversationService.loadCurrentMessages(conversationId),
            targetMessageId,
        )
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.AnswerMessage(
                namespace = resolveNamespace(namespaceValue),
                conversationId = conversationId,
                threadId = conversation.currentThread,
                targetMessageId = targetMessage.id,
            ),
            summary = "Memory question answering queued",
            inputHash = targetMessage.id.value.sha256(),
        )
    }

    suspend fun prepareAnswerProvidedQuestion(
        conversationIdValue: String?,
        questionText: String,
        mode: String? = null,
        namespaceValue: String? = null,
    ): PreparedMemoryOperation {
        val normalizedQuestion = questionText.trim()
        require(normalizedQuestion.isNotBlank()) { "Provided memory question is blank." }
        val conversation = conversationIdValue.toConversationIdOrNull()?.let { requireConversation(it) }
        return PreparedMemoryOperation(
            request = MemoryOperationRequest.AnswerProvidedQuestion(
                namespace = resolveNamespace(namespaceValue),
                conversationId = conversation?.id,
                threadId = conversation?.currentThread,
                question = normalizedQuestion,
                mode = mode,
            ),
            summary = "Memory question answering queued",
            inputHash = normalizedQuestion.sha256(),
        )
    }

    private suspend fun requireConversation(conversationId: Conversation.Id): Conversation =
        conversationService.findById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found: ${conversationId.value}")

    private fun resolveTargetMessage(
        threadMessages: List<Conversation.Message>,
        targetMessageId: String?,
    ): Conversation.Message {
        val explicitMessageId = targetMessageId?.takeIf { it.isNotBlank() }
        if (explicitMessageId != null) {
            return threadMessages.firstOrNull { message ->
                message.id.value == explicitMessageId && !message.isSyntheticMemoryMessage()
            } ?: throw IllegalArgumentException("Target message not found in the current thread: $explicitMessageId")
        }

        return threadMessages
            .asReversed()
            .firstOrNull { message ->
                message.hasUserAuthoredContent() && !message.isSyntheticMemoryMessage()
            }
            ?: throw IllegalArgumentException("No previous user-authored message found in the current thread.")
    }

    private fun resolveNamespace(explicitNamespaceValue: String?): MemoryNamespace =
        explicitNamespaceValue.toMemoryNamespaceOverride() ?: MemoryNamespace.Global

    private fun String?.toConversationIdOrNull(): Conversation.Id? =
        this?.trim()?.takeIf { it.isNotBlank() }?.let(Conversation::Id)

    private fun String?.toMemoryRunIdOrNull(): MemoryRun.Id? =
        this?.trim()?.takeIf { it.isNotBlank() }?.let(MemoryRun::Id)

    private fun Conversation.Message.hasUserAuthoredContent(): Boolean =
        content.any { it is Conversation.Message.ContentItem.UserMessage }

    private fun Conversation.Message.isSyntheticMemoryMessage(): Boolean =
        providerMetadata["syntheticKind"]?.jsonPrimitive?.contentOrNull == "memory"

    private fun MemoryRememberContentInput.identityValue(): String =
        when (this) {
            is MemoryRememberContentInput.Text -> value
            is MemoryRememberContentInput.FilePath -> value
            is MemoryRememberContentInput.RawUrl -> value
        }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
