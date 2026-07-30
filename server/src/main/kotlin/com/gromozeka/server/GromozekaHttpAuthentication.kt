package com.gromozeka.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respondText

internal fun Application.installHttpAuthenticationErrors() {
    install(StatusPages) {
        exception<HttpAuthenticationException> { call, error ->
            error.challenge?.let {
                call.response.headers.append(HttpHeaders.WWWAuthenticate, it)
            }
            call.respondText(
                """{"error":"${error.publicMessage}"}""",
                ContentType.Application.Json,
                error.status,
            )
        }
    }
}

internal class HttpAuthenticationException(
    val status: HttpStatusCode,
    val publicMessage: String,
    val challenge: String? = null,
) : RuntimeException(publicMessage)
