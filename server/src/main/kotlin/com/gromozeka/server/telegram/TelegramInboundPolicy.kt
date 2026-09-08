package com.gromozeka.server.telegram

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.service.ConversationDomainService
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.time.Instant

class TelegramInboundPolicy(
    private val identities: IdentityRepository,
    private val conversations: ConversationDomainService,
) {
    suspend fun prepare(connection: TelegramConnection, update: JsonObject): TelegramInboxMessage? {
        if (!connection.enabled) return null
        val updateId = update.long("update_id") ?: return null
        val edited = update["edited_message"] as? JsonObject
        val message = (update["message"] as? JsonObject) ?: edited ?: return null
        val chat = message["chat"] as? JsonObject ?: return null
        if (chat.string("type") !in setOf("group", "supergroup")) return null
        val binding = connection.bindings.singleOrNull {
            it.enabled && it.chatId == chat.long("id") && it.topicId == message.long("message_thread_id")
        } ?: return null
        val from = message["from"] as? JsonObject
        if (from?.long("id") == connection.botId) return null
        val sourceId = message.long("message_id") ?: return null
        val sentAt = message.long("date") ?: return null
        val timestamp = Instant.fromEpochSeconds(sentAt)
        val anonymous = message["sender_chat"] as? JsonObject
        val authorName = (anonymous?.string("title") ?: listOfNotNull(from?.string("first_name"), from?.string("last_name"))
            .joinToString(" ")).ifBlank { "Telegram" }.take(256)
        val identity = from?.long("id")?.takeIf { it > 0 && anonymous == null }?.let {
            UserIdentity.Telegram(it, authorName, from.string("username"))
        }
        val user = identity?.let { identities.observeTelegramIdentity(it, timestamp) }
        val originalId = Conversation.Message.Id(telegramId(connection.id, binding.chatId, sourceId))
        val messageId = if (edited == null) originalId else Conversation.Message.Id(telegramId(originalId.value, "edit", updateId))
        val conversation = conversations.findById(binding.conversationId) ?: return null
        val attachments = attachments(message).mapNotNull { (kind, file) ->
            val fileId = file.string("file_id") ?: return@mapNotNull null
            val uniqueId = file.string("file_unique_id") ?: return@mapNotNull null
            val mediaType = file.string("mime_type") ?: when (kind) {
                "photo" -> "image/jpeg"; "voice" -> "audio/ogg"; "video", "video_note", "animation" -> "video/mp4"
                "sticker" -> if (file.boolean("is_video")) "video/webm" else if (file.boolean("is_animated")) "application/x-tgsticker" else "image/webp"
                else -> "application/octet-stream"
            }
            Artifact(id = Artifact.Id(telegramId(messageId.value, uniqueId)), projectId = conversation.projectId,
                conversationId = conversation.id, createdByUserId = user?.id,
                fileName = file.string("file_name")?.take(256) ?: "$kind-$sourceId.${mediaType.substringAfter('/').take(20)}",
                mediaType = mediaType, sizeBytes = file.long("file_size")?.coerceAtLeast(0),
                source = Artifact.ContentSource.Telegram(connection.id, fileId, uniqueId),
                purpose = Artifact.Purpose.USER_ATTACHMENT, createdAt = timestamp)
        }
        val text = (message.string("text") ?: message.string("caption")).orEmpty().take(16000)
        val extra = listOfNotNull(
            (message["location"] as? JsonObject)?.let { "[Location: ${it["latitude"]}, ${it["longitude"]}]" },
            (message["poll"] as? JsonObject)?.string("question")?.let { "[Poll: $it]" },
            (message["contact"] as? JsonObject)?.let { "[Contact: ${it.string("first_name").orEmpty()} ${it.string("phone_number").orEmpty()}]" },
        )
        if (text.isEmpty() && extra.isEmpty() && attachments.isEmpty()) return null
        val content = buildList {
            val body = (listOf(text).filter(String::isNotBlank) + extra).joinToString("\n")
            if (body.isNotEmpty()) add(Conversation.Message.ContentItem.UserMessage(if (anonymous != null) "[$authorName]\n$body" else body))
            attachments.forEach { add(Conversation.Message.ContentItem.ArtifactItem(it.reference())) }
        }
        val reply = (message["reply_to_message"] as? JsonObject)
        val imported = Conversation.Message(id = messageId, conversationId = conversation.id, role = Conversation.Message.Role.USER,
            author = user?.let { Conversation.Message.Author.User(it.id, it.displayName, identity!!.key) }, content = content,
            providerMetadata = buildJsonObject {
                put("telegramConnectionId", connection.id); put("telegramMessageId", sourceId)
                put("telegramAuthorName", authorName); identity?.let { put("telegramUserId", it.telegramUserId) }
                reply?.long("message_id")?.let { put("telegramReplyTo", it) }
                reply?.let { quoted ->
                    put("telegramReplyQuotation", buildJsonObject {
                        put("text", (quoted.string("text") ?: quoted.string("caption")).orEmpty().take(8000))
                        (quoted["from"] as? JsonObject)?.long("id")?.let { put("authorId", it) }
                    })
                }
            }, createdAt = timestamp)
        return TelegramInboxMessage(updateId, binding, sourceId, imported, attachments,
            replaceOriginalId = originalId.takeIf { edited != null },
            routes = matchingRoutes(connection, binding, message, edited != null), activationRevision = connection.activationRevision)
    }

    private fun attachments(message: JsonObject): List<Pair<String, JsonObject>> = buildList {
        (message["photo"] as? JsonArray)?.lastOrNull()?.let { if (it is JsonObject) add("photo" to it) }
        for (kind in listOf("document", "audio", "video", "voice", "video_note", "animation", "sticker")) {
            (message[kind] as? JsonObject)?.let { if (none { pair -> pair.second.string("file_unique_id") == it.string("file_unique_id") }) add(kind to it) }
        }
    }

    companion object {
        fun matchingRoutes(connection: TelegramConnection, binding: TelegramConversationBinding, message: JsonObject, edited: Boolean): List<TelegramAgentRoute> {
            val from = message["from"] as? JsonObject ?: return emptyList()
            if (!connection.enabled || !binding.enabled || edited || from.long("id") != binding.initiatorTelegramUserId || from.boolean("is_bot") ||
                (message.long("date") ?: 0) < connection.acceptTriggersAfterEpochSeconds ||
                listOf("sender_chat", "via_bot", "forward_origin", "forward_date").any(message::containsKey) || message.boolean("is_automatic_forward")) return emptyList()
            val text = message.string("text") ?: message.string("caption") ?: return emptyList()
            return binding.routes.filter { text.contains(it.trigger, ignoreCase = true) }
        }
    }
}

internal fun telegramId(vararg parts: Any): String = UUID.nameUUIDFromBytes(("telegram:" + parts.joinToString(":" )).encodeToByteArray()).toString()
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
