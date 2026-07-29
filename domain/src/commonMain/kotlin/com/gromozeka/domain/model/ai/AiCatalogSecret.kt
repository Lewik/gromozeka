package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.SecretRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("slotType")
sealed interface AiCatalogSecretSlot {
    @Serializable
    @SerialName("connection_api_key")
    data class ConnectionApiKey(
        val connectionId: AiConnection.Id,
    ) : AiCatalogSecretSlot

    @Serializable
    @SerialName("brave_search_api_key")
    data object BraveSearchApiKey : AiCatalogSecretSlot

    @Serializable
    @SerialName("jina_reader_api_key")
    data object JinaReaderApiKey : AiCatalogSecretSlot
}

@Serializable
data class AiCatalogSecretState(
    val slot: AiCatalogSecretSlot,
    val source: Source,
    val environmentVariableName: String? = null,
) {
    init {
        require(
            (source == Source.INLINE && environmentVariableName == null) ||
                (source == Source.ENVIRONMENT_VARIABLE && !environmentVariableName.isNullOrBlank())
        ) {
            "AI catalog secret state must describe its source without exposing an inline value"
        }
    }

    @Serializable
    enum class Source {
        INLINE,
        ENVIRONMENT_VARIABLE,
    }
}

@Serializable
@JsonClassDiscriminator("mutationType")
sealed interface AiCatalogSecretMutation {
    val slot: AiCatalogSecretSlot

    @Serializable
    @SerialName("set")
    data class Set(
        override val slot: AiCatalogSecretSlot,
        val value: SecretRef,
    ) : AiCatalogSecretMutation

    @Serializable
    @SerialName("remove")
    data class Remove(
        override val slot: AiCatalogSecretSlot,
    ) : AiCatalogSecretMutation
}

fun AiCatalog.secretStates(): List<AiCatalogSecretState> = buildList {
    connections.forEach { connection ->
        connection.apiKeyOrNull()?.let { secret ->
            add(
                secret.toState(
                    AiCatalogSecretSlot.ConnectionApiKey(connection.id)
                )
            )
        }
    }
    webTools.braveSearch.apiKey?.let { secret ->
        add(secret.toState(AiCatalogSecretSlot.BraveSearchApiKey))
    }
    webTools.jinaReader.apiKey?.let { secret ->
        add(secret.toState(AiCatalogSecretSlot.JinaReaderApiKey))
    }
}

fun AiCatalog.redactInlineSecrets(): AiCatalog =
    copy(
        connections = connections.map { connection ->
            val secret = connection.apiKeyOrNull()
            if (secret is SecretRef.Inline) connection.withApiKey(null) else connection
        },
        webTools = webTools.copy(
            braveSearch = webTools.braveSearch.copy(
                apiKey = webTools.braveSearch.apiKey.takeUnless { it is SecretRef.Inline }
            ),
            jinaReader = webTools.jinaReader.copy(
                apiKey = webTools.jinaReader.apiKey.takeUnless { it is SecretRef.Inline }
            ),
        ),
    )

fun AiCatalogSnapshot.redactInlineSecrets(): AiCatalogSnapshot =
    copy(
        catalog = catalog.redactInlineSecrets(),
        secretStates = catalog.secretStates(),
    )

fun AiConnection.apiKeyOrNull(): SecretRef? =
    (this as? AiConnection.ApiKeyAiConnection)?.apiKey

fun AiConnection.withApiKey(apiKey: SecretRef?): AiConnection =
    when (this) {
        is AiConnection.OpenAiApi -> copy(apiKey = apiKey)
        is AiConnection.OpenAiCompatible -> copy(apiKey = apiKey)
        is AiConnection.AnthropicApi -> copy(apiKey = apiKey)
        is AiConnection.GeminiApi -> copy(apiKey = apiKey)
        else -> {
            require(apiKey == null) {
                "AI connection ${id.value} does not support API keys"
            }
            this
        }
    }

private fun SecretRef.toState(slot: AiCatalogSecretSlot): AiCatalogSecretState =
    when (this) {
        is SecretRef.Inline -> AiCatalogSecretState(
            slot = slot,
            source = AiCatalogSecretState.Source.INLINE,
        )
        is SecretRef.EnvironmentVariable -> AiCatalogSecretState(
            slot = slot,
            source = AiCatalogSecretState.Source.ENVIRONMENT_VARIABLE,
            environmentVariableName = name,
        )
    }
