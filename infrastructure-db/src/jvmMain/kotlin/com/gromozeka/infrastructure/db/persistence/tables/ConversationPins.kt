package com.gromozeka.infrastructure.db.persistence.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

internal object ConversationPins : Table("conversation_pins") {
    val id = varchar("id", 255)
    val conversationId = varchar("conversation_id", 255).references(Conversations.id, onDelete = ReferenceOption.CASCADE)
    val text = text("text")
    val ordinal = long("ordinal").autoIncrement()
    val revision = long("revision")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val sourceConversationId = varchar("source_conversation_id", 255).nullable()
    val sourceMessageId = varchar("source_message_id", 255).nullable()
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(conversationId, ordinal) }
}

internal object ConversationPinCreations : Table("conversation_pin_creations") {
    val conversationId = varchar("conversation_id", 255).references(Conversations.id, onDelete = ReferenceOption.CASCADE)
    val actorId = varchar("actor_id", 255)
    val creationKey = varchar("creation_key", 512)
    val payloadSha256 = varchar("payload_sha256", 64)
    val pinId = varchar("pin_id", 255)
    override val primaryKey = PrimaryKey(conversationId, actorId, creationKey)
}
