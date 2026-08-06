package com.gromozeka.infrastructure.db.persistence.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlinx.datetime.Instant

internal object Projects : Table("projects") {
    val id = varchar("id", 255)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at")

    override val primaryKey = PrimaryKey(id)
}

internal object Users : Table("users") {
    val id = varchar("id", 255)
    val username = varchar("username", 128).uniqueIndex()
    val displayName = varchar("display_name", 255)
    val status = varchar("status", 32)
    val role = varchar("role", 32)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object ProjectMemberships : Table("project_memberships") {
    val projectId = varchar("project_id", 255)
        .references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val userId = varchar("user_id", 255)
        .references(Users.id, onDelete = ReferenceOption.CASCADE)
    val role = varchar("role", 32)
    val createdAt = timestamp("created_at")
    val createdByUserId = varchar("created_by_user_id", 255)
        .references(Users.id, onDelete = ReferenceOption.RESTRICT)

    override val primaryKey = PrimaryKey(projectId, userId)
}

internal object Workers : Table("workers") {
    val id = varchar("id", 64)
    val displayName = varchar("display_name", 255)
    val ownerUserId = varchar("owner_user_id", 255)
        .references(Users.id, onDelete = ReferenceOption.RESTRICT)
    val runtimeWideAccess = bool("runtime_wide_access")
    val status = varchar("status", 32)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object WorkerUserGrants : Table("worker_user_grants") {
    val workerId = varchar("worker_id", 64)
        .references(Workers.id, onDelete = ReferenceOption.CASCADE)
    val userId = varchar("user_id", 255)
        .references(Users.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at")
    val createdByUserId = varchar("created_by_user_id", 255)
        .references(Users.id, onDelete = ReferenceOption.RESTRICT)

    override val primaryKey = PrimaryKey(workerId, userId)
}

internal object WorkerProjectGrants : Table("worker_project_grants") {
    val workerId = varchar("worker_id", 64)
        .references(Workers.id, onDelete = ReferenceOption.CASCADE)
    val projectId = varchar("project_id", 255)
        .references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at")
    val createdByUserId = varchar("created_by_user_id", 255)
        .references(Users.id, onDelete = ReferenceOption.RESTRICT)

    override val primaryKey = PrimaryKey(workerId, projectId)
}

internal object WorkerEnrollmentTokens : Table("worker_enrollment_tokens") {
    val tokenHash = varchar("token_hash", 64)
    val ownerUserId = varchar("owner_user_id", 255)
        .references(Users.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val consumedAt = timestamp("consumed_at").nullable()

    override val primaryKey = PrimaryKey(tokenHash)
}

internal object LocalPasswordCredentials : Table("local_password_credentials") {
    val userId = varchar("user_id", 255).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val passwordHash = text("password_hash")
    val passwordChangedAt = timestamp("password_changed_at")

    override val primaryKey = PrimaryKey(userId)
}

internal object UserSessions : Table("user_sessions") {
    val id = varchar("id", 255)
    val userId = varchar("user_id", 255).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val createdAt = timestamp("created_at")
    val lastSeenAt = timestamp("last_seen_at")
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()
    val clientLabel = varchar("client_label", 255).nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object PersonalAccessTokens : Table("personal_access_tokens") {
    val id = varchar("id", 255)
    val userId = varchar("user_id", 255).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 128)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val tokenPrefix = varchar("token_prefix", 32)
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at").nullable()
    val lastUsedAt = timestamp("last_used_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object PersonalAccessTokenScopes : Table("personal_access_token_scopes") {
    val tokenId = varchar("token_id", 255)
        .references(PersonalAccessTokens.id, onDelete = ReferenceOption.CASCADE)
    val scope = varchar("scope", 64)

    override val primaryKey = PrimaryKey(tokenId, scope)
}

internal object SecurityAuditEvents : Table("security_audit_events") {
    val id = varchar("id", 255)
    val occurredAt = timestamp("occurred_at")
    val actorUserId = varchar("actor_user_id", 255)
        .references(Users.id, onDelete = ReferenceOption.RESTRICT)
    val action = varchar("action", 64)
    val targetType = varchar("target_type", 64)
    val targetId = varchar("target_id", 255)
    val projectId = varchar("project_id", 255).nullable()
    val attributesJson = text("attributes_json")

    override val primaryKey = PrimaryKey(id)
}

internal object Workspaces : Table("workspaces") {
    val id = varchar("id", 255)
    val projectId = varchar("project_id", 255).references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val kind = varchar("kind", 50)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object WorkspaceMounts : Table("workspace_mounts") {
    val id = varchar("id", 255)
    val workspaceId = varchar("workspace_id", 255).references(Workspaces.id, onDelete = ReferenceOption.CASCADE)
    val projectId = varchar("project_id", 255)
    val workerId = varchar("worker_id", 255)
    val rootPath = varchar("root_path", 1000)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object Contexts : Table("contexts") {
    val id = varchar("id", 255)
    val projectId = varchar("project_id", 255).references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val content = text("content")
    val filesJson = text("files_json")
    val linksJson = text("links_json")
    val tags = text("tags")
    val extractedAt = timestamp("extracted_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object Agents : Table("agents") {
    val id = varchar("id", 255)
    val projectId = varchar("project_id", 255)
        .references(Projects.id, onDelete = ReferenceOption.CASCADE)
        .nullable()
    val name = varchar("name", 255)
    val promptsJson = text("prompts_json")  // JSON array of Prompt IDs
    val skillsJson = text("skills_json")
    val runtimeSelectionJson = text("runtime_selection_json")
    val runtimeOverridesJson = text("runtime_overrides_json")
    val toolsJson = text("tools_json")
    val description = text("description").nullable()
    val type = varchar("type", 50)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object AgentSkills : Table("agent_skills") {
    val id = varchar("id", 255)
    val projectId = varchar("project_id", 255).references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 64)
    val description = text("description")
    val instructions = text("instructions")
    val license = text("license").nullable()
    val compatibility = text("compatibility").nullable()
    val metadataJson = text("metadata_json")
    val allowedTools = text("allowed_tools").nullable()
    val contentHash = varchar("content_hash", 64)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object AgentSkillFiles : Table("agent_skill_files") {
    val skillId = varchar("skill_id", 255).references(AgentSkills.id, onDelete = ReferenceOption.CASCADE)
    val path = varchar("path", 1000)
    val content = binary("content")

    override val primaryKey = PrimaryKey(skillId, path)
}

internal object Conversations : Table("conversations") {
    val id = varchar("id", 255)
    val projectId = varchar("project_id", 255).references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val agentDefinitionId = varchar("agent_definition_id", 255).references(Agents.id)
    val displayName = varchar("display_name", 255)
    val currentThreadId = varchar("current_thread_id", 255)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object Artifacts : Table("artifacts") {
    val id = varchar("id", 255)
    val projectId = varchar("project_id", 255).references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val conversationId = varchar("conversation_id", 255)
        .references(Conversations.id, onDelete = ReferenceOption.CASCADE)
    val createdByUserId = varchar("created_by_user_id", 255)
        .references(Users.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()
    val fileName = varchar("file_name", 255)
    val mediaType = varchar("media_type", 255)
    val sizeBytes = long("size_bytes")
    val sha256 = varchar("sha256", 64)
    val purpose = varchar("purpose", 64)
    val state = varchar("state", 32)
    val createdAt = timestamp("created_at")
    val committedAt = timestamp("committed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object AiConnections : Table("ai_connections") {
    val id = varchar("id", 255)
    val payloadJson = text("payload_json")

    override val primaryKey = PrimaryKey(id)
}

internal object AiModelSpecs : Table("ai_model_specs") {
    val provider = varchar("provider", 50)
    val modelId = varchar("model_id", 255)
    val payloadJson = text("payload_json")

    override val primaryKey = PrimaryKey(provider, modelId)
}

internal object AiModelConfigurations : Table("ai_model_configurations") {
    val id = varchar("id", 255)
    val connectionId = varchar("connection_id", 255).references(AiConnections.id)
    val payloadJson = text("payload_json")

    override val primaryKey = PrimaryKey(id)
}

internal object AiRuntimeAssignments : Table("ai_runtime_assignments") {
    val purpose = varchar("purpose", 100)
    val modelConfigurationId = varchar("model_configuration_id", 255).references(AiModelConfigurations.id)
    val payloadJson = text("payload_json")

    override val primaryKey = PrimaryKey(purpose)
}

internal object RuntimeCatalogConfiguration : Table("runtime_catalog_configuration") {
    val id = varchar("id", 32)
    val defaultAgentId = varchar("default_agent_id", 255).references(Agents.id)
    val webToolsJson = text("web_tools_json")
    val revision = long("revision")

    override val primaryKey = PrimaryKey(id)
}

internal object McpServers : Table("mcp_servers") {
    val id = varchar("id", 64)
    val workerId = varchar("worker_id", 255)
    val revision = long("revision")
    val refreshAvailable = bool("refresh_available")
    val payloadJson = text("payload_json")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object AiToolCapabilityCatalogs : Table("ai_tool_capability_catalogs") {
    val sourceId = varchar("source_id", 255)
    val fingerprint = varchar("fingerprint", 64)
    val modelConfigurationId = varchar("model_configuration_id", 255)
    val payloadJson = text("payload_json")
    val generatedAt = timestamp("generated_at")

    override val primaryKey = PrimaryKey(sourceId, fingerprint)
}

internal object AiToolContracts : Table("ai_tool_contracts") {
    val fingerprint = varchar("fingerprint", 64)
    val logicalName = varchar("logical_name", 255)
    val modelName = varchar("model_name", 64)
    val variant = integer("variant")
    val sourceId = varchar("source_id", 255)
    val payloadJson = text("payload_json")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(fingerprint)
}

internal object ConversationTabLayouts : Table("conversation_tab_layouts") {
    val userId = varchar("user_id", 255).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val conversationIdsJson = text("conversation_ids_json")
    val revision = long("revision")
    val updatedAt = timestamp("updated_at").nullable()

    override val primaryKey = PrimaryKey(userId)
}

internal object Threads : Table("threads") {
    val id = varchar("id", 255)
    val conversationId = varchar("conversation_id", 255).references(Conversations.id, onDelete = ReferenceOption.CASCADE)
    val originalThreadId = varchar("original_thread_id", 255).nullable()
    val lastTurnNumber = integer("last_turn_number").default(0)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object ThreadMessages : Table("thread_messages") {
    val threadId = varchar("thread_id", 255).references(Threads.id, onDelete = ReferenceOption.CASCADE)
    val messageId = varchar("message_id", 255).references(Messages.id)
    val position = integer("position")

    override val primaryKey = PrimaryKey(threadId, messageId)
}

internal object Messages : Table("messages") {
    val id = varchar("id", 255)
    val conversationId = varchar("conversation_id", 255).references(Conversations.id, onDelete = ReferenceOption.CASCADE)
    val originalIdsJson = text("original_ids_json")
    val replyToId = varchar("reply_to_id", 255).nullable()
    val squashOperationId = varchar("squash_operation_id", 255).nullable()
    val role = varchar("role", 50)
    val createdAt = timestamp("created_at")
    val messageJson = text("message_json")

    override val primaryKey = PrimaryKey(id)
}

internal object SquashOperations : Table("squash_operations") {
    val id = varchar("id", 255)
    val conversationId = varchar("conversation_id", 255).references(Conversations.id, onDelete = ReferenceOption.CASCADE)
    val sourceMessageIdsJson = text("source_message_ids")
    val resultMessageId = varchar("result_message_id", 255).references(Messages.id)
    val prompt = text("prompt").nullable()
    val model = varchar("model", 255).nullable()
    val performedByAgent = bool("performed_by_agent")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

internal object ToolExecutions : Table("tool_executions") {
    val id = varchar("id", 255)
    val conversationId = varchar("conversation_id", 255).references(Conversations.id, onDelete = ReferenceOption.CASCADE)
    val messageId = varchar("message_id", 255)
    val toolName = varchar("tool_name", 100)
    val input = text("input")
    val output = text("output").nullable()
    val executedAt = timestamp("executed_at")
    val completedAt = timestamp("completed_at").nullable()
    val durationMs = long("duration_ms").nullable()
    val status = varchar("status", 50)
    val error = text("error").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object TokenUsageStatisticsTable : Table("token_usage_statistics") {
    val id = varchar("id", 255)
    val threadId = varchar("thread_id", 255).references(Threads.id, onDelete = ReferenceOption.CASCADE)
    val lastMessageId = varchar("last_message_id", 255).references(Messages.id, onDelete = ReferenceOption.CASCADE)
    val timestamp = timestamp("timestamp")
    val promptTokens = integer("prompt_tokens")
    val completionTokens = integer("completion_tokens")
    val cacheCreationTokens = integer("cache_creation_tokens").default(0)
    val cacheReadTokens = integer("cache_read_tokens").default(0)
    val thinkingTokens = integer("thinking_tokens").default(0)
    val provider = varchar("provider", 50)
    val modelId = varchar("model_id", 100)

    override val primaryKey = PrimaryKey(id)
}

internal object EmbeddingCache : Table("embedding_cache") {
    val textHash = varchar("text_hash", 64)
    val embeddingVector = text("embedding_vector")
    val model = varchar("model", 100)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(textHash, model)
}
