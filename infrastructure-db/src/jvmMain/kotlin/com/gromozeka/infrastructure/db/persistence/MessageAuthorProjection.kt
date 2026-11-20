package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.infrastructure.db.persistence.tables.UserIdentities
import com.gromozeka.infrastructure.db.persistence.tables.Users
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

internal fun resolveCurrentMessageAuthors(messages: List<Conversation.Message>): List<Conversation.Message> {
    val authors = messages.mapNotNull { it.author as? Conversation.Message.Author.User }
    val resolved = resolveAuthors(authors)
    return messages.map { message ->
        val current = resolved[message.author] ?: return@map message
        if (current == message.author) message else message.copy(author = current)
    }
}

internal fun resolveAuthors(authors: List<Conversation.Message.Author.User>): Map<Conversation.Message.Author.User, Conversation.Message.Author.User> {
    if (authors.isEmpty()) return emptyMap()
    val identityKeys = authors.mapNotNull { it.identityKey }.distinct()
    val identityOwners = if (identityKeys.isEmpty()) emptyMap() else UserIdentities.selectAll()
        .where { UserIdentities.key inList identityKeys }
        .associate { it[UserIdentities.key] to it[UserIdentities.userId] }
    val userIds = (authors.map { it.userId.value } + identityOwners.values).distinct()
    val names = Users.selectAll().where { Users.id inList userIds }
        .associate { it[Users.id] to it[Users.displayName] }
    return authors.distinct().associateWith { author ->
        val currentId = identityOwners[author.identityKey] ?: author.userId.value
        names[currentId]?.let { author.copy(userId = User.Id(currentId), displayName = it) } ?: author
    }
}
