package com.gromozeka.server

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Enumeration

/** Runs before CSRF/client authentication. No Host-derived issuer and no unbounded form parsing. */
internal class McpOAuthBoundaryFilter(private val oauth: McpOAuthSettings, private val http: ServerHttpSettings) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Content-Security-Policy", "default-src 'none'; form-action 'self' https: http://localhost:* http://127.0.0.1:* http://[::1]:*; frame-ancestors 'none'; base-uri 'none'")
        if (!request.isSecureTransport(http)) {
            response.writeOAuthError("invalid_request", HttpStatus.UPGRADE_REQUIRED); return
        }
        if (!matchesHost(request.getHeader("Host"))) {
            response.writeOAuthError("invalid_request", HttpStatus.BAD_REQUEST); return
        }
        if (request.getHeader("DPoP") != null) {
            // This profile uses Bearer + refresh rotation; do not silently downgrade a DPoP request.
            response.writeOAuthError("invalid_request", HttpStatus.BAD_REQUEST); return
        }
        try {
            if (request.method == "POST") {
                if (request.contentType?.substringBefore(';')?.trim() != "application/x-www-form-urlencoded" ||
                    request.contentLengthLong > MAX_FORM_BYTES) {
                    response.writeOAuthError("invalid_request", HttpStatus.BAD_REQUEST); return
                }
                val bytes = request.inputStream.readNBytes(MAX_FORM_BYTES + 1)
                if (bytes.size > MAX_FORM_BYTES) {
                    response.writeOAuthError("invalid_request", HttpStatus.BAD_REQUEST); return
                }
                if (request.requestURI in setOf("/oauth2/token", "/oauth2/revoke") && !request.queryString.isNullOrEmpty()) {
                    response.writeOAuthError("invalid_request", HttpStatus.BAD_REQUEST); return
                }
                val form = try { OAuthFormRequest(request, bytes) } catch (_: java.nio.charset.CharacterCodingException) {
                    response.writeOAuthError("invalid_request", HttpStatus.BAD_REQUEST); return
                }
                chain.doFilter(form, response)
            } else chain.doFilter(request, response)
        } catch (_: OAuth2AuthenticationException) {
            if (!response.isCommitted) response.writeOAuthError("invalid_request", HttpStatus.BAD_REQUEST)
        } catch (_: IllegalArgumentException) {
            if (!response.isCommitted) response.writeOAuthError("invalid_request", HttpStatus.BAD_REQUEST)
        }
    }

    private fun matchesHost(value: String?): Boolean = runCatching {
        if (value == null) return false
        val expected = URI(oauth.issuer)
        val received = URI("${expected.scheme}://$value")
        val defaultPort = if (expected.scheme == "https") 443 else 80
        received.rawUserInfo == null && received.rawPath.orEmpty().isEmpty() && received.rawQuery == null &&
            received.rawFragment == null && received.host.equals(expected.host, ignoreCase = true) &&
            (if (received.port == -1) defaultPort else received.port) ==
            (if (expected.port == -1) defaultPort else expected.port)
    }.getOrDefault(false)

    private companion object { const val MAX_FORM_BYTES = 16 * 1024 }
}

internal class McpOAuthTokenTransactionFilter(
    private val authorizations: McpOAuthAuthorizationService,
    private val settings: McpOAuthSettings,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || request.requestURI !in setOf("/oauth2/token", "/oauth2/revoke")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val token = try {
            if (request.requestURI == "/oauth2/revoke") request.singleOAuthParameter("token", McpOAuthSettings.MAX_TOKEN_LENGTH)
            else {
                if (!settings.acceptsResource(request.singleOAuthParameter("resource", 2048))) {
                    throw oauthFailure("invalid_target", "Invalid resource")
                }
                when (request.singleOAuthParameter("grant_type", 100)) {
                    "authorization_code" -> request.singleOAuthParameter("code", McpOAuthSettings.MAX_TOKEN_LENGTH)
                    "refresh_token" -> request.singleOAuthParameter("refresh_token", McpOAuthSettings.MAX_TOKEN_LENGTH)
                    else -> throw oauthFailure("unsupported_grant_type", "Unsupported grant")
                }
            }
        } catch (error: OAuth2AuthenticationException) {
            response.writeOAuthError(error.error.errorCode, HttpStatus.BAD_REQUEST); return
        }
        val buffered = ContentCachingResponseWrapper(response)
        try {
            authorizations.inTokenTransaction(token) {
                chain.doFilter(request, buffered)
                check(!request.isAsyncStarted) { "OAuth protocol must complete within its transaction" }
            }
            // Never publish an access/refresh token before its transaction commits.
            buffered.copyBodyToResponse()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!response.isCommitted) {
                buffered.resetBuffer()
                response.writeOAuthError("server_error", HttpStatus.SERVICE_UNAVAILABLE)
            }
        }
    }
}

internal fun HttpServletResponse.writeOAuthError(code: String, status: HttpStatus = HttpStatus.BAD_REQUEST) {
    setHeader("Cache-Control", "no-store")
    writeJson(buildJsonObject { put("error", code); put("error_description", "OAuth request rejected") }, status)
}

/** The body is bounded before the container/Spring is allowed to build a parameter map. */
private class OAuthFormRequest(request: HttpServletRequest, private val bytes: ByteArray) : HttpServletRequestWrapper(request) {
    private val parameters = LinkedHashMap<String, MutableList<String>>().apply {
        fun add(encoded: String) {
            encoded.split('&').filter(String::isNotEmpty).forEach { pair ->
                val parts = pair.split('=', limit = 2)
                val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                val value = URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                getOrPut(key) { mutableListOf() }.add(value)
            }
        }
        add(request.queryString.orEmpty())
        add(bytes.decodeToString(throwOnInvalidSequence = true))
    }
    override fun getParameter(name: String): String? = parameters[name]?.firstOrNull()
    override fun getParameterValues(name: String): Array<String>? = parameters[name]?.toTypedArray()
    override fun getParameterNames(): Enumeration<String> = Collections.enumeration(parameters.keys)
    override fun getParameterMap(): Map<String, Array<String>> = parameters.mapValues { it.value.toTypedArray() }
    override fun getCharacterEncoding(): String = "UTF-8"
    override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
        private val input = ByteArrayInputStream(bytes)
        override fun read(): Int = input.read()
        override fun read(target: ByteArray, off: Int, length: Int): Int = input.read(target, off, length)
        override fun isFinished(): Boolean = input.available() == 0
        override fun isReady(): Boolean = true
        override fun setReadListener(listener: ReadListener) { throw UnsupportedOperationException("OAuth forms are read synchronously") }
    }
    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
}
