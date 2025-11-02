package com.gromozeka.bot.repository.exposed.tables

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Instant

object Projects : Table("projects") {
    val id = varchar("id", 255)
    val path = varchar("path", 500)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val favorite = bool("favorite")
    val archived = bool("archived")
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at")

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
    val extractedAt = timestamp("extracted_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object Agents : Table("agents") {
    val id = varchar("id", 255)
    val name = varchar("name", 255)
    val systemPrompt = text("system_prompt")
    val description = text("description").nullable()
    val isBuiltin = bool("is_builtin")
    val usageCount = integer("usage_count")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object Conversations : Table("conversations") {
    val id = varchar("id", 255)
    val projectId = varchar("project_id", 255).references(Projects.id, onDelete = ReferenceOption.CASCADE)
    val displayName = varchar("display_name", 255)
    val currentThreadId = varchar("current_thread_id", 255)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object Threads : Table("threads") {
    val id = varchar("id", 255)
    val conversationId = varchar("conversation_id", 255).references(Conversations.id, onDelete = ReferenceOption.CASCADE)
    val originalThreadId = varchar("original_thread_id", 255).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object ThreadMessages : Table("thread_messages") {
    val threadId = varchar("thread_id", 255).references(Threads.id, onDelete = ReferenceOption.CASCADE)
    val messageId = varchar("message_id", 255).references(Messages.id)
    val position = integer("position")

    override val primaryKey = PrimaryKey(threadId, messageId)
}

object Messages : Table("messages") {
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

object SquashOperations : Table("squash_operations") {
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

object ToolExecutions : Table("tool_executions") {
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
