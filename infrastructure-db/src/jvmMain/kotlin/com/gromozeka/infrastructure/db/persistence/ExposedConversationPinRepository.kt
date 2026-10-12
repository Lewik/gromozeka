package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.ConversationPinCreation
import com.gromozeka.domain.repository.ConversationPinRepository
import com.gromozeka.infrastructure.db.persistence.tables.ConversationPinCreations
import com.gromozeka.infrastructure.db.persistence.tables.ConversationPins
import com.gromozeka.infrastructure.db.persistence.tables.Conversations
import com.gromozeka.shared.uuid.uuid7
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Service
import java.security.MessageDigest
import kotlin.time.Clock

@Service
class ExposedConversationPinRepository : ConversationPinRepository {
    override suspend fun list(conversationId: Conversation.Id): List<ConversationPin> = dbQuery {
        orderedConversationPins(conversationId)
    }

    override suspend fun get(conversationId: Conversation.Id, pinId: ConversationPin.Id): ConversationPin? = dbQuery {
        findPin(conversationId, pinId)
    }

    override suspend fun create(
        conversationId: Conversation.Id, actor: User.Id, text: String,
        source: ConversationPinSource?, creationKey: String?,
    ): ConversationPinCreation = dbQuery {
        ConversationPinLimits.validateText(text)
        ConversationPinLimits.validateCreationKey(creationKey)
        ConversationPinLimits.validateSource(source)
        lockPinOwner(conversationId)
        val fingerprint = payloadFingerprint(text, source)
        if (creationKey != null) {
            val receipt = ConversationPinCreations.selectAll().where {
                (ConversationPinCreations.conversationId eq conversationId.value) and
                    (ConversationPinCreations.actorId eq actor.value) and
                    (ConversationPinCreations.creationKey eq creationKey)
            }.singleOrNull()
            if (receipt != null) {
                if (receipt[ConversationPinCreations.payloadSha256] != fingerprint) throw ConversationPinConflictException()
                val pin = findPin(conversationId, ConversationPin.Id(receipt[ConversationPinCreations.pinId]))
                    ?: throw ConversationPinNotFoundException()
                return@dbQuery ConversationPinCreation(pin, created = false)
            }
        }
        val current = orderedConversationPins(conversationId)
        if (current.size >= ConversationPinLimits.MAX_PINS_PER_CONVERSATION ||
            current.sumOf { it.text.length.toLong() } + text.length > ConversationPinLimits.MAX_TOTAL_TEXT_LENGTH) {
            throw ConversationPinLimitException()
        }
        val id = ConversationPin.Id(uuid7())
        val now = Clock.System.now()
        ConversationPins.insert {
            it[ConversationPins.id] = id.value
            it[ConversationPins.conversationId] = conversationId.value
            it[ConversationPins.text] = text
            it[revision] = 1
            it[createdAt] = now
            it[updatedAt] = now
            it[sourceConversationId] = source?.conversationId?.value
            it[sourceMessageId] = source?.messageId?.value
        }
        if (creationKey != null) ConversationPinCreations.insert {
            it[ConversationPinCreations.conversationId] = conversationId.value
            it[actorId] = actor.value
            it[ConversationPinCreations.creationKey] = creationKey
            it[payloadSha256] = fingerprint
            it[pinId] = id.value
        }
        ConversationPinCreation(checkNotNull(findPin(conversationId, id)), created = true)
    }

    override suspend fun update(
        conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long, text: String,
    ): ConversationPin = dbQuery {
        require(expectedRevision > 0) { "Conversation pin revision must be positive" }
        ConversationPinLimits.validateText(text)
        lockPinOwner(conversationId)
        val current = findPin(conversationId, pinId) ?: throw ConversationPinNotFoundException()
        if (current.revision != expectedRevision) throw ConversationPinConflictException()
        if (current.text == text) return@dbQuery current
        if (current.revision == Long.MAX_VALUE) throw ConversationPinConflictException()
        if (orderedConversationPins(conversationId).sumOf { it.text.length.toLong() } - current.text.length + text.length >
            ConversationPinLimits.MAX_TOTAL_TEXT_LENGTH) throw ConversationPinLimitException()
        val next = current.copy(text = text, revision = current.revision + 1, updatedAt = Clock.System.now())
        val changed = ConversationPins.update({
            (ConversationPins.conversationId eq conversationId.value) and (ConversationPins.id eq pinId.value) and
                (ConversationPins.revision eq expectedRevision)
        }) {
            it[ConversationPins.text] = next.text
            it[revision] = next.revision
            it[updatedAt] = next.updatedAt
        }
        if (changed != 1) throw ConversationPinConflictException()
        next
    }

    override suspend fun delete(conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long): Unit = dbQuery {
        require(expectedRevision > 0) { "Conversation pin revision must be positive" }
        lockPinOwner(conversationId)
        val current = findPin(conversationId, pinId) ?: throw ConversationPinNotFoundException()
        if (current.revision != expectedRevision) throw ConversationPinConflictException()
        val deleted = ConversationPins.deleteWhere {
            (ConversationPins.conversationId eq conversationId.value) and (ConversationPins.id eq pinId.value) and (ConversationPins.revision eq expectedRevision)
        }
        if (deleted != 1) throw ConversationPinConflictException()
        // Keep creation receipts so a delayed duplicate cannot recreate this pin.
    }

    private fun findPin(owner: Conversation.Id, id: ConversationPin.Id): ConversationPin? =
        ConversationPins.selectAll().where {
            (ConversationPins.conversationId eq owner.value) and (ConversationPins.id eq id.value)
        }.singleOrNull()?.toConversationPin()

    private fun payloadFingerprint(text: String, source: ConversationPinSource?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Length framing distinguishes every optional provenance/text combination, including embedded delimiters.
        listOf(text, source?.conversationId?.value, source?.messageId?.value).forEach { value ->
            if (value == null) digest.update("-1:".toByteArray(Charsets.UTF_8)) else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8)); digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Call only inside the caller's dbQuery; copy and all mutations use exactly this lock. */
internal fun lockPinOwner(id: Conversation.Id): ResultRow =
    Conversations.selectAll().where { Conversations.id eq id.value }.forUpdate().singleOrNull()
        ?: throw ConversationPinNotFoundException()

internal fun orderedConversationPins(id: Conversation.Id): List<ConversationPin> =
    ConversationPins.selectAll().where { ConversationPins.conversationId eq id.value }
        .orderBy(ConversationPins.ordinal, SortOrder.ASC).map { it.toConversationPin() }

/** One batch in the new Conversation's transaction; no creation receipts or mutable references are shared. */
internal fun copyConversationPins(source: Conversation.Id, target: Conversation.Id) {
    val pins = orderedConversationPins(source)
    val now = Clock.System.now()
    ConversationPins.batchInsert(pins) { pin ->
        this[ConversationPins.id] = uuid7()
        this[ConversationPins.conversationId] = target.value
        this[ConversationPins.text] = pin.text
        this[ConversationPins.ordinal] = pin.ordinal
        this[ConversationPins.revision] = 1
        this[ConversationPins.createdAt] = now
        this[ConversationPins.updatedAt] = now
        this[ConversationPins.sourceConversationId] = pin.source?.conversationId?.value
        this[ConversationPins.sourceMessageId] = pin.source?.messageId?.value
    }
}

private fun ResultRow.toConversationPin(): ConversationPin = ConversationPin(
    id = ConversationPin.Id(this[ConversationPins.id]),
    conversationId = Conversation.Id(this[ConversationPins.conversationId]),
    text = this[ConversationPins.text], ordinal = this[ConversationPins.ordinal], revision = this[ConversationPins.revision],
    createdAt = this[ConversationPins.createdAt], updatedAt = this[ConversationPins.updatedAt],
    source = this[ConversationPins.sourceConversationId]?.let { owner ->
        ConversationPinSource(Conversation.Id(owner), Conversation.Message.Id(checkNotNull(this[ConversationPins.sourceMessageId])))
    },
)
