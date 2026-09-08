package com.gromozeka.infrastructure.db.persistence.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

internal object TranslationStates : Table("translation_states") {
    val userId = varchar("user_id", 255).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val revision = long("revision")
    val payloadJson = text("payload_json")

    override val primaryKey = PrimaryKey(userId)
}
