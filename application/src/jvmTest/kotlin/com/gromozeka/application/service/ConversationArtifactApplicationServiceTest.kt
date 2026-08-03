package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ArtifactRepository
import com.gromozeka.domain.service.ArtifactContentStore
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationArtifactApplicationServiceTest {
    private val repository = InMemoryArtifactRepository()
    private val contentStore = InMemoryArtifactContentStore()
    private val service = ConversationArtifactApplicationService(repository, contentStore)
    private val conversation = conversation("conversation-1", "project-1")

    @Test
    fun `upload stores immutable bytes and materializes an image for the runtime`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val artifact = service.upload(
            conversation = conversation,
            createdByUserId = User.Id("user-1"),
            upload = ArtifactUpload(
                fileName = "../capture.png",
                mediaType = "image/png; charset=binary",
                content = bytes,
                purpose = Artifact.Purpose.USER_SCREENSHOT,
            ),
        )

        assertEquals("capture.png", artifact.fileName)
        assertEquals("image/png", artifact.mediaType)
        assertEquals(Artifact.Purpose.USER_SCREENSHOT, artifact.purpose)
        assertEquals(Artifact.State.DRAFT, artifact.state)
        assertContentEquals(bytes, contentStore.read(artifact.id))

        val message = userMessage(
            conversation.id,
            Conversation.Message.ContentItem.ArtifactItem(artifact.reference()),
        )
        service.validateReferences(conversation.id, message.content)
        assertEquals(Artifact.State.COMMITTED, repository.findById(artifact.id)?.state)
        val materialized = service.materialize(conversation.id, listOf(message)).single()
        val image = assertIs<Conversation.Message.ContentItem.ImageItem>(materialized.content.single())
        val source = assertIs<Conversation.Message.ImageSource.Base64ImageSource>(image.source)

        assertEquals("image/png", source.mediaType)
        assertContentEquals(bytes, Base64.getDecoder().decode(source.data))
    }

    @Test
    fun `tool screenshot is persisted as an artifact and materialized as binary tool content`() = runBlocking {
        val bytes = byteArrayOf(9, 8, 7)
        val result = Conversation.Message.ContentItem.ToolResult(
            toolUseId = Conversation.Message.ContentItem.ToolCall.Id("call-1"),
            toolName = "grz_capture_screenshot",
            result = listOf(
                Conversation.Message.ContentItem.ToolResult.Data.Base64Data(
                    data = Base64.getEncoder().encodeToString(bytes),
                    mediaType = Conversation.Message.MediaType.parse("image/png"),
                    fileName = "worker-screen.png",
                )
            ),
            isError = false,
        )

        val persisted = service.persistAndCommitToolResults(conversation, null, listOf(result)).single()
        val reference = assertIs<Conversation.Message.ContentItem.ToolResult.Data.ArtifactData>(
            persisted.result.single()
        ).artifact

        assertEquals(Artifact.Purpose.TOOL_SCREENSHOT, reference.purpose)
        assertEquals(Artifact.State.COMMITTED, repository.findById(reference.id)?.state)
        assertContentEquals(bytes, contentStore.read(reference.id))

        val message = userMessage(conversation.id, persisted)
        val materialized = service.materialize(conversation.id, listOf(message)).single()
        val binary = assertIs<Conversation.Message.ContentItem.ToolResult.Data.Base64Data>(
            assertIs<Conversation.Message.ContentItem.ToolResult>(materialized.content.single()).result.single()
        )
        assertEquals("worker-screen.png", binary.fileName)
        assertContentEquals(bytes, Base64.getDecoder().decode(binary.data))
    }

    @Test
    fun `artifact from another conversation is rejected before content reaches a provider`() = runBlocking {
        val artifact = service.upload(
            conversation = conversation,
            createdByUserId = null,
            upload = ArtifactUpload(
                fileName = "notes.txt",
                mediaType = "text/plain",
                content = "private".encodeToByteArray(),
                purpose = Artifact.Purpose.USER_ATTACHMENT,
            ),
        )
        val otherConversation = conversation("conversation-2", "project-1")
        val message = userMessage(
            otherConversation.id,
            Conversation.Message.ContentItem.ArtifactItem(artifact.reference()),
        )

        assertFailsWith<IllegalArgumentException> {
            service.materialize(otherConversation.id, listOf(message))
        }
        Unit
    }

    @Test
    fun `fork clones each referenced artifact once and rewrites message references`() = runBlocking {
        val bytes = "shared attachment".encodeToByteArray()
        val source = service.upload(
            conversation = conversation,
            createdByUserId = User.Id("user-1"),
            upload = ArtifactUpload(
                fileName = "shared.txt",
                mediaType = "text/plain",
                content = bytes,
                purpose = Artifact.Purpose.USER_ATTACHMENT,
            ),
        )
        val message = userMessage(
            conversation.id,
            Conversation.Message.ContentItem.ArtifactItem(source.reference()),
        ).copy(
            content = listOf(
                Conversation.Message.ContentItem.ArtifactItem(source.reference()),
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = Conversation.Message.ContentItem.ToolCall.Id("call-1"),
                    toolName = "test_tool",
                    result = listOf(
                        Conversation.Message.ContentItem.ToolResult.Data.ArtifactData(source.reference())
                    ),
                ),
            )
        )
        val target = conversation("conversation-fork", "project-1")
        service.validateReferences(conversation.id, message.content)

        val cloned = service.cloneReferences(conversation.id, target, listOf(message)).single()
        val directReference = assertIs<Conversation.Message.ContentItem.ArtifactItem>(cloned.content[0]).artifact
        val toolReference = assertIs<Conversation.Message.ContentItem.ToolResult.Data.ArtifactData>(
            assertIs<Conversation.Message.ContentItem.ToolResult>(cloned.content[1]).result.single()
        ).artifact

        assertEquals(directReference.id, toolReference.id)
        assertEquals(1, repository.findByConversation(target.id).size)
        assertContentEquals(bytes, contentStore.read(directReference.id))
        assertEquals(Artifact.State.COMMITTED, repository.findById(directReference.id)?.state)
    }

    @Test
    fun `reference metadata mismatch is rejected without committing draft`() = runBlocking {
        val artifact = service.upload(
            conversation = conversation,
            createdByUserId = null,
            upload = ArtifactUpload(
                fileName = "notes.txt",
                mediaType = "text/plain",
                content = "notes".encodeToByteArray(),
                purpose = Artifact.Purpose.USER_ATTACHMENT,
            ),
        )
        val spoofed = artifact.reference().copy(mediaType = "image/png", kind = Artifact.Kind.IMAGE)

        assertFailsWith<IllegalArgumentException> {
            service.validateReferences(
                conversation.id,
                listOf(Conversation.Message.ContentItem.ArtifactItem(spoofed)),
            )
        }
        assertEquals(Artifact.State.DRAFT, repository.findById(artifact.id)?.state)
    }

    @Test
    fun `draft can be removed but committed artifact cannot`() = runBlocking {
        val artifact = service.upload(
            conversation = conversation,
            createdByUserId = null,
            upload = ArtifactUpload(
                fileName = "draft.txt",
                mediaType = "text/plain",
                content = "draft".encodeToByteArray(),
                purpose = Artifact.Purpose.USER_ATTACHMENT,
            ),
        )

        assertEquals(
            ConversationArtifactApplicationService.DraftDeletionResult.DELETED,
            service.deleteDraft(conversation.id, artifact.id),
        )
        assertNull(repository.findById(artifact.id))
        assertFailsWith<IllegalArgumentException> { contentStore.read(artifact.id) }

        val committed = service.upload(
            conversation = conversation,
            createdByUserId = null,
            upload = ArtifactUpload(
                fileName = "committed.txt",
                mediaType = "text/plain",
                content = "committed".encodeToByteArray(),
                purpose = Artifact.Purpose.USER_ATTACHMENT,
            ),
        )
        service.validateReferences(
            conversation.id,
            listOf(Conversation.Message.ContentItem.ArtifactItem(committed.reference())),
        )
        assertEquals(
            ConversationArtifactApplicationService.DraftDeletionResult.ALREADY_COMMITTED,
            service.deleteDraft(conversation.id, committed.id),
        )
    }

    @Test
    fun `message artifact limits are checked before drafts are committed`() = runBlocking {
        val artifact = service.upload(
            conversation = conversation,
            createdByUserId = null,
            upload = ArtifactUpload(
                fileName = "repeated.txt",
                mediaType = "text/plain",
                content = "content".encodeToByteArray(),
                purpose = Artifact.Purpose.USER_ATTACHMENT,
            ),
        )
        val content = List(ArtifactLimits.MAX_ARTIFACTS_PER_MESSAGE + 1) {
            Conversation.Message.ContentItem.ArtifactItem(artifact.reference())
        }

        assertFailsWith<IllegalArgumentException> {
            service.validateReferences(conversation.id, content)
        }
        assertEquals(Artifact.State.DRAFT, repository.findById(artifact.id)?.state)
    }

    @Test
    fun `failed content write leaves no artifact metadata`() = runBlocking {
        val failingService = ConversationArtifactApplicationService(repository, FailingArtifactContentStore)

        assertFailsWith<IllegalStateException> {
            failingService.upload(
                conversation = conversation,
                createdByUserId = null,
                upload = ArtifactUpload(
                    fileName = "broken.txt",
                    mediaType = "text/plain",
                    content = "broken".encodeToByteArray(),
                    purpose = Artifact.Purpose.USER_ATTACHMENT,
                ),
            )
        }
        assertTrue(repository.findByConversation(conversation.id).isEmpty())
    }

    @Test
    fun `garbage collection removes expired drafts and orphaned content`() = runBlocking {
        val draft = service.upload(
            conversation = conversation,
            createdByUserId = null,
            upload = ArtifactUpload(
                fileName = "stale.txt",
                mediaType = "text/plain",
                content = "stale".encodeToByteArray(),
                purpose = Artifact.Purpose.USER_ATTACHMENT,
            ),
        )
        repository.replace(draft.copy(createdAt = Instant.fromEpochMilliseconds(1)))
        val orphanId = Artifact.Id("orphan")
        contentStore.write(orphanId, "orphan".encodeToByteArray())

        val result = service.collectGarbage(Instant.fromEpochMilliseconds(2))

        assertEquals(1, result.deletedDrafts)
        assertEquals(1, result.deletedOrphanedContent)
        assertNull(repository.findById(draft.id))
        assertTrue(contentStore.listIds().isEmpty())
    }

    private fun conversation(id: String, projectId: String): Conversation = Conversation(
        id = Conversation.Id(id),
        projectId = Project.Id(projectId),
        agentDefinitionId = AgentDefinition.Id("agent-1"),
        currentThread = Conversation.Thread.Id("thread-$id"),
        createdAt = Instant.fromEpochMilliseconds(1),
        updatedAt = Instant.fromEpochMilliseconds(1),
    )

    private fun userMessage(
        conversationId: Conversation.Id,
        content: Conversation.Message.ContentItem,
    ): Conversation.Message = Conversation.Message(
        id = Conversation.Message.Id("message-${conversationId.value}"),
        conversationId = conversationId,
        role = Conversation.Message.Role.USER,
        content = listOf(content),
        createdAt = Instant.fromEpochMilliseconds(2),
    )
}

private class InMemoryArtifactRepository : ArtifactRepository {
    private val artifacts = linkedMapOf<Artifact.Id, Artifact>()

    override suspend fun save(artifact: Artifact): Artifact = artifact.also { artifacts[it.id] = it }

    override suspend fun findById(id: Artifact.Id): Artifact? = artifacts[id]

    override suspend fun findByIds(ids: List<Artifact.Id>): List<Artifact> = ids.mapNotNull(artifacts::get)

    override suspend fun findByConversation(conversationId: Conversation.Id): List<Artifact> =
        artifacts.values.filter { it.conversationId == conversationId }

    override suspend fun commit(ids: List<Artifact.Id>, committedAt: Instant) {
        ids.distinct().forEach { id ->
            val artifact = requireNotNull(artifacts[id])
            if (artifact.state == Artifact.State.DRAFT) {
                artifacts[id] = artifact.copy(
                    state = Artifact.State.COMMITTED,
                    committedAt = committedAt,
                )
            }
        }
    }

    override suspend fun deleteDraft(id: Artifact.Id): Boolean {
        val artifact = artifacts[id] ?: return false
        if (artifact.state != Artifact.State.DRAFT) return false
        artifacts.remove(id)
        return true
    }

    override suspend fun findDraftsCreatedBefore(createdBefore: Instant, limit: Int): List<Artifact> =
        artifacts.values
            .filter { it.state == Artifact.State.DRAFT && it.createdAt < createdBefore }
            .take(limit)

    fun replace(artifact: Artifact) {
        artifacts[artifact.id] = artifact
    }
}

private class InMemoryArtifactContentStore : ArtifactContentStore {
    private val content = mutableMapOf<Artifact.Id, ByteArray>()

    override suspend fun write(id: Artifact.Id, content: ByteArray) {
        this.content[id] = content.copyOf()
    }

    override suspend fun read(id: Artifact.Id): ByteArray =
        requireNotNull(content[id]).copyOf()

    override suspend fun delete(id: Artifact.Id) {
        content.remove(id)
    }

    override suspend fun listIds(): Set<Artifact.Id> = content.keys.toSet()
}

private object FailingArtifactContentStore : ArtifactContentStore {
    override suspend fun write(id: Artifact.Id, content: ByteArray) {
        error("Storage unavailable")
    }

    override suspend fun read(id: Artifact.Id): ByteArray = error("Storage unavailable")

    override suspend fun delete(id: Artifact.Id) = Unit

    override suspend fun listIds(): Set<Artifact.Id> = emptySet()
}
