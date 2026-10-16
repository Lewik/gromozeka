package com.gromozeka.server

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolAccessPolicy

import com.gromozeka.domain.model.TelegramConnection
import com.gromozeka.domain.model.TelegramProfileUpdate
import com.gromozeka.domain.service.TelegramManagementService
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service

@Service
internal class TelegramManagementToolContributor(
    private val toolFactory: ServerJsonToolFactory,
    private val telegram: TelegramManagementService,
) : AiToolCallbackContributor {
    override val callbacks = listOf(
        toolFactory.create(CONTROL_TOOL_SOURCE, "grz_telegram_list", "List your Telegram bot connections, revisions and eligible project conversations with connected agents. Telegram network access is opt-in per Server deployment. Bot tokens are existing named secrets and are never returned.",
            ServerToolSchemas.objectSchema(emptyMap()), readOnly = true, accessPolicy = AiToolAccessPolicy.SERVER_OWNER) {
            Json.encodeToJsonElement(telegram.snapshot(user)).jsonObject
        },
        toolFactory.create(CONTROL_TOOL_SOURCE, "grz_telegram_probe", "Read bot identity and public profile using one of your existing named secrets containing a BotFather token. Does not connect or start the bot. Requires Telegram enabled in the deployment.",
            ServerToolSchemas.objectSchema(mapOf("tokenSecretName" to ServerToolSchemas.string("Existing secret name, without secret://.")), listOf("tokenSecretName")),
            readOnly = true, accessPolicy = AiToolAccessPolicy.SERVER_OWNER) { input ->
            Json.encodeToJsonElement(telegram.probe(user, input.requiredString("tokenSecretName"))).jsonObject
        },
        toolFactory.create(CONTROL_TOOL_SOURCE, "grz_telegram_configure", "Save a complete Telegram connection with optimistic revision control. Read grz_telegram_list first. A connection has botId, botUsername, ownerUserId, tokenSecretName, enabled, revision and bindings. Each binding has chatId, optional topicId, conversationId, initiatorTelegramUserId, enabled, locale and routes. Each route has agentId, an exact case-insensitive trigger substring (no automatic @), contextPercent 1..80, writeAllowed and optional additionalInstruction. Only the configured initiator can trigger; fixed channel safety instructions cannot be replaced. Multiple matching routes run sequentially once each. Connected conversations are read-only in Gromozeka UI. Settings apply live; disabling does not delete history.",
            ServerToolSchemas.objectSchema(mapOf("connection" to ServerToolSchemas.objectValue("Complete TelegramConnection object. Use the numeric identity returned by grz_telegram_probe for a new bot."),
                "expectedRevision" to ServerToolSchemas.integer("Latest connection revision; 0 for a new connection.", minimum = 0)), listOf("connection", "expectedRevision")),
            readOnly = false, idempotent = false, accessPolicy = AiToolAccessPolicy.SERVER_OWNER) { input ->
            val connection = Json.decodeFromJsonElement<TelegramConnection>(input.getValue("connection"))
            Json.encodeToJsonElement(telegram.save(user, connection, input.requiredLong("expectedRevision"))).jsonObject
        },
        toolFactory.create(CONTROL_TOOL_SOURCE, "grz_telegram_profile_get", "Read a connected bot's public name and descriptions. These are global across all Telegram groups, not conversation-specific.",
            ServerToolSchemas.objectSchema(mapOf("connectionId" to ServerToolSchemas.string("Connection id returned by grz_telegram_list.")), listOf("connectionId")),
            readOnly = true, accessPolicy = AiToolAccessPolicy.SERVER_OWNER) { input ->
            Json.encodeToJsonElement(telegram.profile(user, input.requiredString("connectionId"))).jsonObject
        },
        toolFactory.create(CONTROL_TOOL_SOURCE, "grz_telegram_profile_update", "Update a bot's global name (1..64 characters), description (up to 512) and shortDescription (up to 120). Optional avatarArtifactId selects an image artifact you can read; it is converted to a square JPEG with a dark background. Omit avatarArtifactId to keep the avatar. Telegram Bot API does not expose bot accent-color settings. Other groups using this bot see the same profile. Partial API failures can leave some fields updated; read the current profile before retrying.",
            ServerToolSchemas.objectSchema(mapOf("connectionId" to ServerToolSchemas.string("Existing connection id."),
                "profile" to ServerToolSchemas.objectValue("Object with name, description, shortDescription and optional avatarArtifactId.")), listOf("connectionId", "profile")),
            readOnly = false, idempotent = true, accessPolicy = AiToolAccessPolicy.SERVER_OWNER) { input ->
            val profile = Json.decodeFromJsonElement<TelegramProfileUpdate>(input.getValue("profile"))
            Json.encodeToJsonElement(telegram.updateProfile(user, input.requiredString("connectionId"), profile)).jsonObject
        },
    )
}
