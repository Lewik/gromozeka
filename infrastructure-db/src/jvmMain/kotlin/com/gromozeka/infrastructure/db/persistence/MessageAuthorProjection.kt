package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.infrastructure.db.persistence.tables.UserIdentities
import com.gromozeka.infrastructure.db.persistence.tables.Users
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

internal fun resolveCurrentMessageAuthors(messages: List<Conversation.Message>): List<Conversation.Message> {
    val authors = messages.mapNotNull { it.author as? Conversation.Message.Author.User }
    if (authors.isEmpty()) return messages
    val identityKeys = authors.mapNotNull { it.identityKey }.distinct()
    val identityOwners = if (identityKeys.isEmpty()) emptyMap() else UserIdentities.selectAll()
        .where { UserIdentities.key inList identityKeys }
        .associate { it[UserIdentities.key] to it[UserIdentities.userId] }
    val userIds = (authors.map { it.userId.value } + identityOwners.values).distinct()
    val names = Users.selectAll().where { Users.id inList userIds }
        .associate { it[Users.id] to it[Users.displayName] }
    return messages.map { message ->
        val author = message.author as? Conversation.Message.Author.User ?: return@map message
        val currentId = identityOwners[author.identityKey] ?: author.userId.value
        val name = names[currentId] ?: return@map message
        message.copy(author = author.copy(userId = User.Id(currentId), displayName = name))
    }
}
