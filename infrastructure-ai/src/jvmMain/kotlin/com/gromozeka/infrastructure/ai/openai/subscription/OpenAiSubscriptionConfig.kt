package com.gromozeka.infrastructure.ai.openai.subscription

import kotlinx.serialization.Serializable

@Serializable
data class OpenAiSubscriptionConfig(
    val clientId: String = DEFAULT_CLIENT_ID,
    val issuer: String = DEFAULT_ISSUER,
    val redirectUri: String = DEFAULT_REDIRECT_URI,
    val scope: String = DEFAULT_SCOPE,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val accountId: String? = null,
    val expiresAt: Long? = null,
) {
    companion object {
        const val DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val DEFAULT_ISSUER = "https://auth.openai.com"
        const val DEFAULT_REDIRECT_URI = "http://localhost:1455/callback"
        const val DEFAULT_SCOPE = "openid profile email offline_access"
    }
}

data class OpenAiSubscriptionSession(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String?,
    val accountId: String?,
    val expiresAt: Long,
)

data class OpenAiSubscriptionAuthorizationUrl(
    val url: String,
    val verifier: String,
    val state: String,
)
