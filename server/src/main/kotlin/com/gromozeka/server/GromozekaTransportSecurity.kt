package com.gromozeka.server

import io.ktor.server.application.ApplicationCall

internal fun ApplicationCall.isSecureTransport(): Boolean {
    val connection = request.local
    return connection.scheme.equals("https", ignoreCase = true) ||
        connection.remoteAddress in loopbackAddresses
}

private val loopbackAddresses = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
