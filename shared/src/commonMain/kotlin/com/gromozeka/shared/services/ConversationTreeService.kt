package com.gromozeka.shared.services

import com.gromozeka.shared.domain.conversation.ConversationTree
import com.gromozeka.shared.domain.project.Project
import com.gromozeka.shared.logging.logger
import com.gromozeka.shared.repository.ConversationTreeRepository
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Instant

class ConversationTreeService(
    private val repository: ConversationTreeRepository,
    private val projectService: ProjectService
) {
    private val log = logger(this)

    suspend fun createTree(
        projectPath: String,
        displayName: String? = null,
        parentConversation: ConversationTree.Id? = null,
        branchFromMessage: ConversationTree.Message.Id? = null
    ): ConversationTree {
        val project = projectService.getOrCreate(projectPath)
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val tree = ConversationTree(
            id = ConversationTree.Id(uuid7()),
            projectId = project.id,
            displayName = displayName,
            parentConversation = parentConversation,
            branchFromMessage = branchFromMessage,
            createdAt = now,
            updatedAt = now
        )

        return repository.save(tree)
    }

    suspend fun save(tree: ConversationTree): ConversationTree =
        repository.save(tree)

    suspend fun findById(id: ConversationTree.Id): ConversationTree? =
        repository.findById(id)

    suspend fun findByProject(projectPath: String): List<ConversationTree> {
        val project = projectService.findByPath(projectPath) ?: return emptyList()
        return repository.findByProject(project.id)
    }

    suspend fun addMessage(
        treeId: ConversationTree.Id,
        message: ConversationTree.Message
    ): ConversationTree? {
        val tree = repository.findById(treeId) ?: return null

        val updated = tree.copy(
            messages = tree.messages + message,
            updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )

        return repository.save(updated)
    }

    suspend fun updateMessages(
        treeId: ConversationTree.Id,
        messages: List<ConversationTree.Message>
    ): ConversationTree? {
        val tree = repository.findById(treeId) ?: return null

        val updated = tree.copy(
            messages = messages,
            updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )

        return repository.save(updated)
    }

    suspend fun delete(id: ConversationTree.Id) {
        repository.delete(id)
    }

    suspend fun updateDisplayName(
        treeId: ConversationTree.Id,
        displayName: String?
    ): ConversationTree? {
        val tree = repository.findById(treeId) ?: return null
        return repository.save(tree.copy(
            displayName = displayName,
            updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        ))
    }

    suspend fun updateHead(
        treeId: ConversationTree.Id,
        head: ConversationTree.Message.Id?
    ): ConversationTree? {
        val tree = repository.findById(treeId) ?: return null
        return repository.save(tree.copy(
            head = head,
            updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        ))
    }

    suspend fun updateBranchSelections(
        treeId: ConversationTree.Id,
        branchSelections: Set<ConversationTree.Message.Id>
    ): ConversationTree? {
        val tree = repository.findById(treeId) ?: return null
        return repository.save(tree.copy(
            branchSelections = branchSelections,
            updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        ))
    }
}
