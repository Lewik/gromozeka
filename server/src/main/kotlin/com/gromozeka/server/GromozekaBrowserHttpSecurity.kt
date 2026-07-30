package com.gromozeka.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import java.net.URI

internal val gromozekaBrowserSecurityHeaders = createApplicationPlugin(
    name = "GromozekaBrowserSecurityHeaders",
) {
    onCall { call ->
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.response.headers.append("X-Frame-Options", "DENY")
        call.response.headers.append(
            "Permissions-Policy",
            "camera=(self), microphone=(self), geolocation=(self)",
        )
    }
}

internal val gromozekaBrowserOriginProtection = createRouteScopedPlugin(
    name = "GromozekaBrowserOriginProtection",
) {
    onCall { call ->
        if (!call.hasAllowedBrowserOrigin()) {
            throw HttpAuthenticationException(
                status = HttpStatusCode.Forbidden,
                publicMessage = "Cross-origin browser request rejected",
            )
        }
    }
}

internal fun io.ktor.server.application.ApplicationCall.hasAllowedBrowserOrigin(): Boolean =
    isAllowedBrowserOrigin(
        origin = request.headers[HttpHeaders.Origin],
        host = request.headers[HttpHeaders.Host],
    )

internal fun isAllowedBrowserOrigin(
    origin: String?,
    host: String?,
): Boolean {
    if (origin == null) return true
    if (origin.equals("null", ignoreCase = true) || host.isNullOrBlank()) return false

    val originUri = origin.toHttpOriginUri() ?: return false
    val hostUri = "${originUri.scheme.lowercase()}://$host".toHttpOriginUri() ?: return false
    return originUri.host.equals(hostUri.host, ignoreCase = true) &&
        originUri.effectivePort() == hostUri.effectivePort()
}

private fun String.toHttpOriginUri(): URI? {
    val uri = runCatching { URI(this) }.getOrNull() ?: return null
    if (!uri.scheme.equals("http", ignoreCase = true) &&
        !uri.scheme.equals("https", ignoreCase = true)
    ) {
        return null
    }
    if (
        uri.host.isNullOrBlank() ||
        uri.userInfo != null ||
        uri.rawQuery != null ||
        uri.rawFragment != null ||
        (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/")
    ) {
        return null
    }
    return uri
}

private fun URI.effectivePort(): Int =
    when {
        port >= 0 -> port
        scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
