package com.gromozeka.server

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.net.URI

internal fun HttpServletRequest.hasAllowedBrowserOrigin(): Boolean =
    isAllowedBrowserOrigin(origin = getHeader(HttpHeaders.ORIGIN), host = getHeader(HttpHeaders.HOST))

internal fun HttpServletRequest.requireAllowedBrowserOrigin() {
    if (!hasAllowedBrowserOrigin()) {
        throw HttpAuthenticationException(HttpStatus.FORBIDDEN, "Cross-origin browser request rejected")
    }
}

internal fun isAllowedBrowserOrigin(origin: String?, host: String?): Boolean {
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
        !uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.rawQuery != null ||
        uri.rawFragment != null || (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/")) return null
    return uri
}

private fun URI.effectivePort(): Int = when {
    port >= 0 -> port
    scheme.equals("https", ignoreCase = true) -> 443
    else -> 80
}
