package com.gromozeka.infrastructure.db.persistence.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

internal object Prompts : Table("prompts") {
    val id = varchar("id", 255)
    val name = varchar("name", 255)
    val content = text("content")
    val sourceType = varchar("source_type", 50)
    val sourcePath = text("source_path").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
