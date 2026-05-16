package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

/**
 * AI model/vendor family.
 *
 * This is not an account, endpoint, authentication method, or transport
 * protocol. Bedrock, subscription login, API key auth, and OpenAI-compatible
 * HTTP are connection kinds, not providers.
 */
@Serializable
enum class AiProvider {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    OLLAMA,
    CUSTOM,
}
