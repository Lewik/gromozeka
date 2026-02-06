package com.gromozeka.infrastructure.ai.oauth

import kotlinx.serialization.Serializable

@Serializable
data class OAuthConfig(
    val enabled: Boolean,
    val clientId: String,
    val redirectUri: String,
    val authorizeUrlMax: String,
    val authorizeUrlConsole: String,
    val tokenUrl: String,
    val scope: String,
    val betaHeaders: String,
    val toolPrefix: String,
    val userAgent: String,
    val accessToken: String?,
    val refreshToken: String?,
    val expiresAt: Long?
)
