package com.gromozeka.application.service

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ArtifactRepository
import com.gromozeka.domain.service.ArtifactContentStore
import com.gromozeka.domain.service.ArtifactReferenceValidator
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.Base64

@Service
class ConversationArtifactApplicationService(
    private val artifactRepository: ArtifactRepository,
    private val contentStore: ArtifactContentStore,
) : ArtifactReferenceValidator {
    suspend fun upload(
        conversation: Conversation,
        createdByUserId: User.Id?,
        upload: ArtifactUpload,
    ): Artifact {
        val content = upload.content
        require(content.isNotEmpty()) { "Artifact content must not be empty" }
        require(content.size <= ArtifactLimits.MAX_FILE_BYTES) {
            "Artifact exceeds the ${ArtifactLimits.MAX_FILE_BYTES / (1024 * 1024)} MB limit"
        }
        val fileName = upload.fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .take(MAX_FILE_NAME_LENGTH)
        require(fileName.isNotBlank()) { "Artifact file name must not be blank" }
        val mediaType = upload.mediaType.substringBefore(';').trim().lowercase()
        require(mediaType.matches(MEDIA_TYPE_PATTERN)) { "Invalid artifact media type" }

        val artifact = Artifact(
            id = Artifact.Id(uuid7()),
            projectId = conversation.projectId,
            conversationId = conversation.id,
            createdByUserId = createdByUserId,
            fileName = fileName,
            mediaType = mediaType,
            sizeBytes = content.size.toLong(),
            sha256 = content.sha256(),
            purpose = upload.purpose,
            createdAt = Clock.System.now(),
        )

        artifactRepository.save(artifact)
        return runCatching {
            contentStore.write(artifact.id, content)
            artifact
        }
            .getOrElse { error ->
                runCatching { artifactRepository.deleteDraft(artifact.id) }
                throw error
            }
    }

    suspend fun find(id: Artifact.Id): Artifact? = artifactRepository.findById(id)

    suspend fun read(id: Artifact.Id): ByteArray {
        val artifact = artifactRepository.findById(id)
            ?: error("Artifact not found: ${id.value}")
        return contentStore.read(artifact.id)
    }

    suspend fun deleteDraft(
        conversationId: Conversation.Id,
        id: Artifact.Id,
    ): DraftDeletionResult {
        val artifact = artifactRepository.findById(id) ?: return DraftDeletionResult.NOT_FOUND
        require(artifact.conversationId == conversationId) {
            "Artifact ${artifact.id.value} belongs to another conversation"
        }
        if (artifact.state != Artifact.State.DRAFT) return DraftDeletionResult.ALREADY_COMMITTED
        if (!artifactRepository.deleteDraft(id)) {
            return when (artifactRepository.findById(id)?.state) {
                Artifact.State.COMMITTED -> DraftDeletionResult.ALREADY_COMMITTED
                Artifact.State.DRAFT -> error("Draft artifact could not be deleted: ${id.value}")
                null -> DraftDeletionResult.NOT_FOUND
            }
        }
        contentStore.delete(id)
        return DraftDeletionResult.DELETED
    }

    suspend fun persistAndCommitToolResults(
        conversation: Conversation,
        createdByUserId: User.Id?,
        results: List<Conversation.Message.ContentItem.ToolResult>,
    ): List<Conversation.Message.ContentItem.ToolResult> {
        val persisted = results.map { toolResult ->
            toolResult.copy(
                result = toolResult.result.map data@{ data ->
                    if (data !is Conversation.Message.ContentItem.ToolResult.Data.Base64Data) {
                        return@data data
                    }
                    val artifact = upload(
                        conversation = conversation,
                        createdByUserId = createdByUserId,
                        upload = ArtifactUpload(
                            fileName = data.fileName ?: toolResult.defaultArtifactFileName(data.mediaType),
                            mediaType = data.mediaType.value,
                            content = Base64.getDecoder().decode(data.data),
                            purpose = if (toolResult.effectiveToolName in SCREENSHOT_TOOL_NAMES) {
                                Artifact.Purpose.TOOL_SCREENSHOT
                            } else {
                                Artifact.Purpose.TOOL_OUTPUT
                            },
                        ),
                    )
                    Conversation.Message.ContentItem.ToolResult.Data.ArtifactData(artifact.reference())
                }
            )
        }
        validateReferences(conversation.id, persisted)
        return persisted
    }

    suspend fun materialize(
        conversationId: Conversation.Id,
        messages: List<Conversation.Message>,
    ): List<Conversation.Message> {
        val allReferences = messages.flatMap { it.content }.contentArtifactReferences()
        if (allReferences.isEmpty()) return messages

        val artifacts = requireArtifactReferences(conversationId, allReferences)
        require(artifacts.values.all { it.state == Artifact.State.COMMITTED }) {
            "Only committed artifacts can be sent to an AI provider"
        }
        val runtimeMessages = messages.withBoundedComputerUseScreenshots()
        val references = runtimeMessages.flatMap { it.content }.contentArtifactReferences()
        val artifactIds = references.map(Artifact.Reference::id).distinct()

        val materialized = mutableMapOf<Artifact.Id, MaterializedArtifact>()
        for (id in artifactIds) {
            val artifact = artifacts.getValue(id)
            val encoded = Base64.getEncoder().encodeToString(contentStore.read(id))
            materialized[id] = MaterializedArtifact(artifact, encoded)
        }

        return runtimeMessages.map { message ->
            message.copy(
                content = message.content.map { item ->
                    when (item) {
                        is Conversation.Message.ContentItem.ArtifactItem ->
                            materialized.getValue(item.artifact.id).asContentItem()

                        is Conversation.Message.ContentItem.ToolResult -> item.copy(
                            result = item.result.map { data ->
                                if (data is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData) {
                                    materialized.getValue(data.artifact.id).asToolResultData()
                                } else {
                                    data
                                }
                            }
                        )

                        else -> item
                    }
                }
            )
        }
    }

    private fun List<Conversation.Message>.withBoundedComputerUseScreenshots(): List<Conversation.Message> {
        val screenshots = flatMap { message ->
            message.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
                .filter { it.effectiveToolName in COMPUTER_USE_SCREENSHOT_TOOL_NAMES }
                .flatMap { result ->
                    result.result
                        .filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.ArtifactData>()
                        .filter { it.artifact.kind == Artifact.Kind.IMAGE }
                        .map { it.artifact.id }
                }
        }
        val omitted = screenshots.dropLast(RECENT_COMPUTER_USE_SCREENSHOT_LIMIT).toSet()
        if (omitted.isEmpty()) return this

        return map { message ->
            message.copy(
                content = message.content.map { item ->
                    if (item !is Conversation.Message.ContentItem.ToolResult ||
                        item.effectiveToolName !in COMPUTER_USE_SCREENSHOT_TOOL_NAMES
                    ) {
                        item
                    } else {
                        val containsOmittedScreenshot = item.result
                            .filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.ArtifactData>()
                            .any { it.artifact.id in omitted }
                        if (containsOmittedScreenshot) {
                            item.copy(
                                result = listOf(
                                    Conversation.Message.ContentItem.ToolResult.Data.Text(
                                        "[Older Computer Use observation omitted; capture a fresh observation if needed.]"
                                    )
                                )
                            )
                        } else {
                            item
                        }
                    }
                }
            )
        }
    }

    override suspend fun validateReferences(
        conversationId: Conversation.Id,
        content: List<Conversation.Message.ContentItem>,
    ) {
        val references = content.contentArtifactReferences()
        if (references.isEmpty()) return

        require(references.size <= ArtifactLimits.MAX_ARTIFACTS_PER_MESSAGE) {
            "A message can contain at most ${ArtifactLimits.MAX_ARTIFACTS_PER_MESSAGE} artifacts"
        }
        val artifacts = requireArtifactReferences(conversationId, references)
        val totalBytes = references.sumOf { reference -> artifacts.getValue(reference.id).sizeBytes }
        require(totalBytes <= ArtifactLimits.MAX_TOTAL_BYTES_PER_MESSAGE) {
            "Message artifacts exceed the ${ArtifactLimits.MAX_TOTAL_BYTES_PER_MESSAGE / (1024 * 1024)} MB total limit"
        }
        artifactRepository.commit(references.map(Artifact.Reference::id), Clock.System.now())
    }

    suspend fun cloneReferences(
        sourceConversationId: Conversation.Id,
        targetConversation: Conversation,
        messages: List<Conversation.Message>,
    ): List<Conversation.Message> {
        val references = messages.flatMap { it.content }.contentArtifactReferences()
        if (references.isEmpty()) return messages

        val sourceArtifacts = requireArtifactReferences(sourceConversationId, references)
        require(sourceArtifacts.values.all { it.state == Artifact.State.COMMITTED }) {
            "Only committed artifacts can be cloned with a conversation"
        }
        val artifactIds = references.map(Artifact.Reference::id).distinct()
        val clonedReferences = linkedMapOf<Artifact.Id, Artifact.Reference>()
        for (artifactId in artifactIds) {
            val source = sourceArtifacts.getValue(artifactId)
            val clone = upload(
                conversation = targetConversation,
                createdByUserId = source.createdByUserId,
                upload = ArtifactUpload(
                    fileName = source.fileName,
                    mediaType = source.mediaType,
                    content = contentStore.read(source.id),
                    purpose = source.purpose,
                ),
            )
            clonedReferences[artifactId] = clone.reference()
        }

        val clonedMessages = messages.map { message ->
            message.copy(content = message.content.replaceArtifactReferences(clonedReferences))
        }
        clonedMessages.forEach { message ->
            validateReferences(targetConversation.id, message.content)
        }
        return clonedMessages
    }

    suspend fun collectGarbage(
        draftsCreatedBefore: kotlinx.datetime.Instant,
        batchSize: Int = DEFAULT_GC_BATCH_SIZE,
    ): GarbageCollectionResult {
        require(batchSize > 0) { "Artifact garbage collection batch size must be positive" }
        val staleDrafts = artifactRepository.findDraftsCreatedBefore(draftsCreatedBefore, batchSize)
        var deletedDrafts = 0
        staleDrafts.forEach { artifact ->
            if (artifactRepository.deleteDraft(artifact.id)) {
                contentStore.delete(artifact.id)
                deletedDrafts++
            }
        }

        val storedIds = contentStore.listIds()
        val registeredIds = artifactRepository.findByIds(storedIds.toList()).map(Artifact::id).toSet()
        val orphanedIds = storedIds - registeredIds
        orphanedIds.forEach { contentStore.delete(it) }

        return GarbageCollectionResult(
            deletedDrafts = deletedDrafts,
            deletedOrphanedContent = orphanedIds.size,
            hasMoreExpiredDrafts = staleDrafts.size == batchSize,
        )
    }

    private suspend fun requireArtifacts(
        conversationId: Conversation.Id,
        artifactIds: List<Artifact.Id>,
    ): Map<Artifact.Id, Artifact> {
        val artifacts = artifactRepository.findByIds(artifactIds).associateBy(Artifact::id)
        val missingIds = artifactIds.filterNot(artifacts::containsKey)
        require(missingIds.isEmpty()) {
            "Conversation references missing artifacts: ${missingIds.joinToString { it.value }}"
        }
        artifacts.values.forEach { artifact ->
            require(artifact.conversationId == conversationId) {
                "Artifact ${artifact.id.value} belongs to another conversation"
            }
        }
        return artifacts
    }

    private suspend fun requireArtifactReferences(
        conversationId: Conversation.Id,
        references: List<Artifact.Reference>,
    ): Map<Artifact.Id, Artifact> {
        val artifacts = requireArtifacts(conversationId, references.map(Artifact.Reference::id).distinct())
        references.forEach { reference ->
            require(artifacts.getValue(reference.id).reference() == reference) {
                "Artifact reference metadata does not match stored artifact: ${reference.id.value}"
            }
        }
        return artifacts
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun Conversation.Message.ContentItem.ToolResult.defaultArtifactFileName(
        mediaType: Conversation.Message.MediaType,
    ): String = "$effectiveToolName-output.${mediaType.defaultExtension()}"

    private val Conversation.Message.ContentItem.ToolResult.effectiveToolName: String
        get() = executionToolName ?: toolName

    private fun Conversation.Message.MediaType.defaultExtension(): String = when (value) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "application/pdf" -> "pdf"
        "application/json" -> "json"
        "text/plain" -> "txt"
        else -> "bin"
    }

    private data class MaterializedArtifact(
        val artifact: Artifact,
        val encoded: String,
    ) {
        fun asContentItem(): Conversation.Message.ContentItem =
            if (artifact.mediaType.startsWith("image/")) {
                Conversation.Message.ContentItem.ImageItem(
                    Conversation.Message.ImageSource.Base64ImageSource(
                        data = encoded,
                        mediaType = artifact.mediaType,
                    )
                )
            } else {
                Conversation.Message.ContentItem.DocumentItem(
                    Conversation.Message.DocumentSource.Base64DocumentSource(
                        data = encoded,
                        mediaType = artifact.mediaType,
                        fileName = artifact.fileName,
                    )
                )
            }

        fun asToolResultData(): Conversation.Message.ContentItem.ToolResult.Data.Base64Data =
            Conversation.Message.ContentItem.ToolResult.Data.Base64Data(
                data = encoded,
                mediaType = Conversation.Message.MediaType.parse(artifact.mediaType),
                fileName = artifact.fileName,
            )
    }

    companion object {
        const val DEFAULT_GC_BATCH_SIZE = 100
        private val SCREENSHOT_TOOL_NAMES = setOf(
            "grz_capture_screenshot",
            "grz_computer_observe",
            "grz_computer_act",
        )
        private val COMPUTER_USE_SCREENSHOT_TOOL_NAMES = setOf(
            "grz_computer_observe",
            "grz_computer_act",
        )
        private const val RECENT_COMPUTER_USE_SCREENSHOT_LIMIT = 3
        private const val MAX_FILE_NAME_LENGTH = 255
        private val MEDIA_TYPE_PATTERN = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
    }

    enum class DraftDeletionResult {
        DELETED,
        NOT_FOUND,
        ALREADY_COMMITTED,
    }

    data class GarbageCollectionResult(
        val deletedDrafts: Int,
        val deletedOrphanedContent: Int,
        val hasMoreExpiredDrafts: Boolean,
    )
}

private fun List<Conversation.Message.ContentItem>.contentArtifactReferences(): List<Artifact.Reference> =
    flatMap { item ->
        when (item) {
            is Conversation.Message.ContentItem.ArtifactItem -> listOf(item.artifact)
            is Conversation.Message.ContentItem.ToolResult -> item.result
                .filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.ArtifactData>()
                .map(Conversation.Message.ContentItem.ToolResult.Data.ArtifactData::artifact)
            else -> emptyList()
        }
    }

private fun List<Conversation.Message.ContentItem>.replaceArtifactReferences(
    replacements: Map<Artifact.Id, Artifact.Reference>,
): List<Conversation.Message.ContentItem> = map { item ->
    when (item) {
        is Conversation.Message.ContentItem.ArtifactItem ->
            item.copy(artifact = replacements.getValue(item.artifact.id))

        is Conversation.Message.ContentItem.ToolResult -> item.copy(
            result = item.result.map { data ->
                if (data is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData) {
                    data.copy(artifact = replacements.getValue(data.artifact.id))
                } else {
                    data
                }
            }
        )

        else -> item
    }
}
