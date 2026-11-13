package com.gromozeka.domain.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

object CommandScriptTokens {
    const val PREFIX = "grz_cmd_"
    val environmentKeys: Set<String> = setOf("GRZ_MCP_URL", "GRZ_MCP_TOKEN", "GRZ_MCP_CA_BUNDLE")
    private val random = SecureRandom()
    private val tokenPattern = Regex("${PREFIX}[A-Za-z0-9_-]{43}")
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun create(): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))

    fun isCommandToken(value: String): Boolean = tokenPattern.matches(value)
    fun requireValidHash(value: String) { require(hashPattern.matches(value)) { "Invalid command SDK token hash" } }
    fun hash(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}

/** Process-only credential. Neither toString nor the registration protocol exposes the raw token. */
class CommandScriptBootstrap(mcpUrl: String, caBundlePath: String? = null) {
    private val token = CommandScriptTokens.create()
    val tokenHash: String = CommandScriptTokens.hash(token)
    val environment: Map<String, String> = buildMap {
        require(mcpUrl.isNotBlank()) { "Command SDK endpoint is required" }
        put("GRZ_MCP_URL", mcpUrl)
        put("GRZ_MCP_TOKEN", token)
        caBundlePath?.let {
            require(it.isNotBlank()) { "Command SDK CA path must not be blank" }
            put("GRZ_MCP_CA_BUNDLE", it)
        }
    }
    override fun toString(): String = "CommandScriptBootstrap(redacted)"
}

/** The Worker prepares the environment, then registers the gated process before releasing it. */
interface CommandScriptBootstrapService {
    fun create(): CommandScriptBootstrap
    suspend fun register(task: CommandTask, tokenHash: String): CommandTaskUpsertResult
}
