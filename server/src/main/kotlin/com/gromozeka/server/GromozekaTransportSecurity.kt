package com.gromozeka.server

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

internal fun ApplicationCall.isSecureTransport(): Boolean {
    val connection = request.local
    return isSecureTransport(
        scheme = connection.scheme,
        remoteAddress = connection.remoteAddress,
        forwardedProto = request.headers[FORWARDED_PROTO_HEADER],
        trustForwardedHttps = application.attributes.getOrNull(trustForwardedHttpsKey) ?: false,
    )
}

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

internal val trustForwardedHttpsKey =
    AttributeKey<Boolean>("GromozekaTrustForwardedHttps")

private val loopbackAddresses = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
private const val FORWARDED_PROTO_HEADER = "X-Forwarded-Proto"
