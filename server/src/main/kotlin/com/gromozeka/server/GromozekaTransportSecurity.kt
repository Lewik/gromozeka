package com.gromozeka.server

import jakarta.servlet.http.HttpServletRequest

internal fun HttpServletRequest.isSecureTransport(settings: ServerHttpSettings): Boolean =
    isSecureTransport(
        scheme = scheme,
        remoteAddress = remoteAddr,
        forwardedProto = getHeader(FORWARDED_PROTO_HEADER),
        trustForwardedHttps = settings.trustForwardedHttps,
    )

internal fun isSecureTransport(
    scheme: String,
    remoteAddress: String,
    forwardedProto: String?,
    trustForwardedHttps: Boolean,
): Boolean =
    scheme.equals("https", ignoreCase = true) ||
        remoteAddress in loopbackAddresses ||
        (trustForwardedHttps &&
            forwardedProto?.trim()?.equals("https", ignoreCase = true) == true)

internal fun resolveTrustForwardedHttps(configured: String?): Boolean {
    if (configured == null) return false
    return configured.toBooleanStrictOrNull()
        ?: error("GROMOZEKA_TRUST_FORWARDED_HTTPS must be true or false")
}

private val loopbackAddresses = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
private const val FORWARDED_PROTO_HEADER = "X-Forwarded-Proto"
