package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.SecretRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.jvm.JvmInline

/**
 * Concrete way to reach an AI provider.
 *
 * A connection is account/endpoint/protocol configuration, not a model choice.
 * Concrete subclasses intentionally encode provider-specific auth and endpoint
 * shape, so impossible combinations fail at compile time instead of leaking into
 * runtime validation.
 */
@Serializable
@JsonClassDiscriminator("connectionKind")
sealed interface AiConnection {
    val id: Id
    val displayName: String
    val enabled: Boolean
    val kind: Kind

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "AI connection id must not be blank" }
        }
    }

    /**
     * Hard-coded way Gromozeka knows how to connect to an AI provider.
     *
     * A kind is compile-time because each value implies adapter code, auth shape,
     * SDK behavior, and supported runtime features. Concrete [AiConnection]
     * values are runtime configuration instances of these kinds.
     */
    @Serializable
    enum class Kind(val provider: AiProvider) {
        OPENAI_API(AiProvider.OPENAI),
        OPENAI_SUBSCRIPTION(AiProvider.OPENAI),
        OPENAI_COMPATIBLE(AiProvider.CUSTOM),
        ANTHROPIC_API(AiProvider.ANTHROPIC),
        ANTHROPIC_BEDROCK(AiProvider.ANTHROPIC),
        CLAUDE_CODE(AiProvider.ANTHROPIC),
        GEMINI_API(AiProvider.GOOGLE),
        OLLAMA(AiProvider.OLLAMA),
    }

    @Serializable
    @SerialName("openai_subscription")
    data class OpenAiSubscription(
        override val id: Id,
        override val displayName: String,
        override val enabled: Boolean = true,
    ) : AiConnection {
        override val kind = Kind.OPENAI_SUBSCRIPTION

        init {
            validateDisplayName(displayName)
        }
    }

    @Serializable
    @SerialName("openai_api")
    data class OpenAiApi(
        override val id: Id,
        override val displayName: String,
        override val enabled: Boolean = true,
        override val baseUrl: String? = null,
        override val apiKey: SecretRef? = null,
    ) : AiConnection, HttpAiConnection, ApiKeyAiConnection {
        override val kind = Kind.OPENAI_API

        init {
            validateDisplayName(displayName)
            validateOptionalBaseUrl(baseUrl)
        }
    }

    @Serializable
    @SerialName("openai_compatible")
    data class OpenAiCompatible(
        override val id: Id,
        override val displayName: String,
        override val enabled: Boolean = true,
        override val baseUrl: String,
        override val apiKey: SecretRef? = null,
    ) : AiConnection, HttpAiConnection, ApiKeyAiConnection {
        override val kind = Kind.OPENAI_COMPATIBLE

        init {
            validateDisplayName(displayName)
            require(baseUrl.isNotBlank()) { "OpenAI-compatible base URL must not be blank" }
        }
    }

    @Serializable
    @SerialName("anthropic_api")
    data class AnthropicApi(
        override val id: Id,
        override val displayName: String,
        override val enabled: Boolean = true,
        override val baseUrl: String? = null,
        override val apiKey: SecretRef? = null,
    ) : AiConnection, HttpAiConnection, ApiKeyAiConnection {
        override val kind = Kind.ANTHROPIC_API

        init {
            validateDisplayName(displayName)
            validateOptionalBaseUrl(baseUrl)
        }
    }

    @Serializable
    @SerialName("anthropic_bedrock")
    data class AnthropicBedrock(
        override val id: Id,
        override val displayName: String,
        override val enabled: Boolean = true,
        override val baseUrl: String? = null,
        override val awsRegion: String? = null,
        override val awsProfile: String? = null,
    ) : AiConnection, HttpAiConnection, AwsAiConnection {
        override val kind = Kind.ANTHROPIC_BEDROCK

        init {
            validateDisplayName(displayName)
            validateOptionalBaseUrl(baseUrl)
            validateOptionalAwsValue("region", awsRegion)
            validateOptionalAwsValue("profile", awsProfile)
        }
    }

    @Serializable
    @SerialName("claude_code")
    data class ClaudeCode(
        override val id: Id,
        override val displayName: String,
        override val enabled: Boolean = true,
    ) : AiConnection {
        override val kind = Kind.CLAUDE_CODE

        init {
            validateDisplayName(displayName)
        }
    }

    @Serializable
    @SerialName("gemini_api")
    data class GeminiApi(
        override val id: Id,
        override val displayName: String,
        override val enabled: Boolean = true,
        override val baseUrl: String? = null,
        override val apiKey: SecretRef? = null,
    ) : AiConnection, HttpAiConnection, ApiKeyAiConnection {
        override val kind = Kind.GEMINI_API

        init {
            validateDisplayName(displayName)
            validateOptionalBaseUrl(baseUrl)
        }
    }

    @Serializable
    @SerialName("ollama")
    data class Ollama(
        override val id: Id,
        override val displayName: String,
        override val enabled: Boolean = true,
        override val baseUrl: String,
    ) : AiConnection, HttpAiConnection {
        override val kind = Kind.OLLAMA

        init {
            validateDisplayName(displayName)
            require(baseUrl.isNotBlank()) { "Ollama base URL must not be blank" }
        }
    }

    interface HttpAiConnection {
        val baseUrl: String?
    }

    interface ApiKeyAiConnection {
        val apiKey: SecretRef?
    }

    interface AwsAiConnection {
        val awsRegion: String?
        val awsProfile: String?
    }

    companion object {
        private fun validateDisplayName(displayName: String) {
            require(displayName.isNotBlank()) { "AI connection display name must not be blank" }
        }

        private fun validateOptionalBaseUrl(baseUrl: String?) {
            require(baseUrl == null || baseUrl.isNotBlank()) { "AI connection base URL must not be blank" }
        }

        private fun validateOptionalAwsValue(name: String, value: String?) {
            require(value == null || value.isNotBlank()) { "AI connection AWS $name must not be blank" }
        }
    }
}
