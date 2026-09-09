package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TelegramConnection(
    val botId: Long,
    val botUsername: String,
    val ownerUserId: User.Id,
    val tokenSecretName: String,
    val enabled: Boolean = false,
    val bindings: List<TelegramConversationBinding> = emptyList(),
    val revision: Long = 0,
    val acceptTriggersAfterEpochSeconds: Long = Long.MAX_VALUE,
    val activationRevision: Long = 0,
) {
    val id: String get() = botId.toString()
    init {
        require(botId > 0)
        require(botUsername.matches(Regex("[A-Za-z0-9_]{5,32}")))
        require(NamedSecret.NAME_PATTERN.matches(tokenSecretName))
        require(revision >= 0)
        require(bindings.map { it.chatId to it.topicId }.distinct().size == bindings.size)
        require(bindings.map { it.conversationId }.distinct().size == bindings.size)
    }
}

@Serializable
data class TelegramConversationBinding(
    val chatId: Long,
    val topicId: Long? = null,
    val conversationId: Conversation.Id,
    val initiatorTelegramUserId: Long,
    val routes: List<TelegramAgentRoute>,
    val enabled: Boolean = true,
    val locale: String = "en",
) {
    val key: String get() = "$chatId:${topicId ?: 0}"
    init {
        require(chatId < 0)
        require(topicId == null || topicId > 0)
        require(initiatorTelegramUserId > 0)
        require(routes.isNotEmpty())
        require(routes.map { it.agentId }.distinct().size == routes.size)
        require(locale.isNotBlank() && locale.length <= 32)
    }

    fun overlappingAliases(): List<Pair<String, String>> = routes.flatMapIndexed { index, route ->
        routes.drop(index + 1).filter { other ->
            route.trigger.contains(other.trigger, ignoreCase = true) || other.trigger.contains(route.trigger, ignoreCase = true)
        }.map { route.trigger to it.trigger }
    }
}

@Serializable
data class TelegramAgentRoute(
    val agentId: AgentDefinition.Id,
    val trigger: String = "@grz",
    val contextPercent: Int = 50,
    val writeAllowed: Boolean = false,
    val additionalInstruction: String = "",
) {
    init {
        require(trigger.isNotBlank() && trigger.length <= 128)
        require(contextPercent in 1..80)
        require(additionalInstruction.length <= 16000)
    }
}

@Serializable
data class TelegramBotProfile(
    val botId: Long,
    val username: String,
    val name: String,
    val description: String,
    val shortDescription: String,
    val canReadAllGroupMessages: Boolean,
)

@Serializable
data class TelegramProfileUpdate(
    val name: String,
    val description: String,
    val shortDescription: String,
    val avatarArtifactId: Artifact.Id? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= 64)
        require(description.length <= 512)
        require(shortDescription.length <= 120)
    }
}

@Serializable
data class TelegramManagementSnapshot(
    val serverEnabled: Boolean,
    val connections: List<TelegramConnection>,
    val targets: List<TelegramConversationTarget> = emptyList(),
)

@Serializable
data class TelegramConversationTarget(
    val conversationId: Conversation.Id,
    val projectName: String,
    val conversationName: String,
    val agents: List<TelegramAgentTarget>,
    val externalChannel: ExternalConversationChannel?,
)

@Serializable
data class TelegramAgentTarget(val agentId: AgentDefinition.Id, val name: String)

@Serializable
data class ExternalConversationChannel(
    val provider: String,
    val connectionId: String,
    val channelKey: String,
) {
    init { require(provider.isNotBlank() && connectionId.isNotBlank() && channelKey.isNotBlank()) }
}
