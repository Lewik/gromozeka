package com.gromozeka.infrastructure.db.persistence.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

internal object UserMessageDeliveryPreferences : Table("user_message_delivery_preferences") {
    val userId = varchar("user_id", 255).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val deliveryMode = varchar("delivery_mode", 32)
    override val primaryKey = PrimaryKey(userId)
}
