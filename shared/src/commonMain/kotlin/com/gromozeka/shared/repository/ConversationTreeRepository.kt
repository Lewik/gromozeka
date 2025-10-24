package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.conversation.ConversationTree
import com.gromozeka.shared.domain.project.Project

interface ConversationTreeRepository {
    suspend fun save(tree: ConversationTree): ConversationTree
    suspend fun findById(id: ConversationTree.Id): ConversationTree?
    suspend fun findByProject(projectId: Project.Id): List<ConversationTree>
    suspend fun delete(id: ConversationTree.Id)
}
