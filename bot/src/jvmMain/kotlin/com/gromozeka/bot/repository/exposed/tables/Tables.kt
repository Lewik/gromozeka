package com.gromozeka.bot.repository.exposed.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object Projects : Table("projects") {
    val id = varchar("id", 255)
    val path = varchar("path", 500)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val favorite = bool("favorite")
    val archived = bool("archived")
    val tags = text("tags")  // JSON array
    val metadataJson = text("metadata_json").nullable()
    val settingsJson = text("settings_json").nullable()
    val statisticsJson = text("statistics_json").nullable()
    val createdAt = long("created_at")
    val lastUsedAt = long("last_used_at")

    override val primaryKey = PrimaryKey(id)
}

object ToolExecutions : Table("tool_executions") {
    val id = varchar("id", 255)
    val conversationId = varchar("conversation_id", 255).references(ConversationTrees.id, onDelete = ReferenceOption.CASCADE)
    val messageId = varchar("message_id", 255)
    val toolName = varchar("tool_name", 100)
    val input = text("input")
    val output = text("output").nullable()
    val executedAt = long("executed_at")
    val completedAt = long("completed_at").nullable()
    val durationMs = long("duration_ms").nullable()
    val status = varchar("status", 50)
    val error = text("error").nullable()

    override val primaryKey = PrimaryKey(id)
}

object Contexts : Table("contexts") {
    val id = varchar("id", 255)
    val projectId = varchar("project_id", 255).references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val content = text("content")
    val filesJson = text("files_json")  // Serialized List<Context.File>
    val linksJson = text("links_json")  // Serialized List<String>
    val tags = text("tags")  // JSON array
    val extractedAt = long("extracted_at")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object Agents : Table("agents") {
    val id = varchar("id", 255)
    val name = varchar("name", 255)
    val systemPrompt = text("system_prompt")
    val description = text("description").nullable()
    val isBuiltin = bool("is_builtin")
    val usageCount = integer("usage_count")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object ConversationTrees : Table("conversation_trees") {
    val id = varchar("id", 255)
    val projectId = varchar("project_id", 255).references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val displayName = varchar("display_name", 255).nullable()

    // Fork support (self-reference, FK constraint will be added via migration)
    val parentConversationId = varchar("parent_conversation_id", 255).nullable()
    val branchFromMessageId = varchar("branch_from_message_id", 255).nullable()

    // Navigation
    val headMessageId = varchar("head_message_id", 255).nullable()
    val branchSelectionsJson = text("branch_selections_json")  // JSON array

    val tags = text("tags")  // JSON array
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object ConversationMessages : Table("conversation_messages") {
    val id = varchar("id", 255)
    val treeId = varchar("tree_id", 255).references(ConversationTrees.id, onDelete = ReferenceOption.CASCADE)
    val parentIdsJson = text("parent_ids_json")  // JSON array
    val role = varchar("role", 50)
    val timestampMs = long("timestamp_ms")
    val messageJson = text("message_json")  // Serialized ConversationTree.Message

    override val primaryKey = PrimaryKey(id)
}
