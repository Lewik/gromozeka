package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.statesync.StateSyncService

interface ConversationRuntimeStateSyncService :
    StateSyncService<Conversation.Id, ConversationRuntimeSnapshot>

interface ActiveGenerationStateSyncService :
    StateSyncService<Conversation.Id, ActiveGenerationSnapshot?>

interface ConversationTabLayoutStateSyncService :
    StateSyncService<User.Id, ConversationTabLayout>

enum class DeclarativeStateResource {
    PROJECTS,
    PROJECT_CONVERSATIONS,
    PROJECT_WORKSPACES,
    WORKSPACE_MOUNTS,
    AGENTS,
    PROMPTS,
    PROJECT_AGENT_SKILLS,
    AI_CATALOG,
    MCP_SERVERS,
    WORKERS,
    USERS,
    USER_DIRECTORY,
    PROJECT_MEMBERSHIPS,
    SETTINGS,
    QUICK_TEXT_ACTIONS,
}

data class DeclarativeStateKey(
    val resource: DeclarativeStateResource,
    val scopeId: String? = null,
) {
    companion object {
        val projects = DeclarativeStateKey(DeclarativeStateResource.PROJECTS)
        val agents = DeclarativeStateKey(DeclarativeStateResource.AGENTS)
        val prompts = DeclarativeStateKey(DeclarativeStateResource.PROMPTS)
        val aiCatalog = DeclarativeStateKey(DeclarativeStateResource.AI_CATALOG)
        val mcpServers = DeclarativeStateKey(DeclarativeStateResource.MCP_SERVERS)
        val workers = DeclarativeStateKey(DeclarativeStateResource.WORKERS)
        val users = DeclarativeStateKey(DeclarativeStateResource.USERS)
        val userDirectory = DeclarativeStateKey(DeclarativeStateResource.USER_DIRECTORY)
        val settings = DeclarativeStateKey(DeclarativeStateResource.SETTINGS)
        val quickTextActions = DeclarativeStateKey(DeclarativeStateResource.QUICK_TEXT_ACTIONS)

        fun projectConversations(projectId: Project.Id) =
            DeclarativeStateKey(DeclarativeStateResource.PROJECT_CONVERSATIONS, projectId.value)

        fun projectWorkspaces(projectId: Project.Id) =
            DeclarativeStateKey(DeclarativeStateResource.PROJECT_WORKSPACES, projectId.value)

        fun workspaceMounts(workspaceId: com.gromozeka.domain.model.Workspace.Id) =
            DeclarativeStateKey(DeclarativeStateResource.WORKSPACE_MOUNTS, workspaceId.value)

        fun projectAgentSkills(projectId: Project.Id) =
            DeclarativeStateKey(DeclarativeStateResource.PROJECT_AGENT_SKILLS, projectId.value)

        fun projectMemberships(projectId: Project.Id) =
            DeclarativeStateKey(DeclarativeStateResource.PROJECT_MEMBERSHIPS, projectId.value)
    }
}

interface DeclarativeStateSyncService : StateSyncService<DeclarativeStateKey, Unit>

fun interface DeclarativeStateInvalidator {
    suspend fun invalidate(key: DeclarativeStateKey)
}

object NoOpDeclarativeStateInvalidator : DeclarativeStateInvalidator {
    override suspend fun invalidate(key: DeclarativeStateKey) = Unit
}

fun interface DeclarativeStateChangePublisher {
    fun publish(vararg keys: DeclarativeStateKey)
}

object NoOpDeclarativeStateChangePublisher : DeclarativeStateChangePublisher {
    override fun publish(vararg keys: DeclarativeStateKey) = Unit
}
