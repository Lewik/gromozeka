package com.gromozeka.server

import com.gromozeka.domain.service.ProjectAccessDeniedException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

internal val httpJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal class HttpAuthenticationException(
    val status: HttpStatus,
    val publicMessage: String,
    val challenge: String? = null,
) : RuntimeException(publicMessage)

internal class HttpRequestBodyTooLarge : RuntimeException("Request is too large")

/** Bounds the bytes actually read, including requests without Content-Length. */
internal suspend fun HttpServletRequest.readBoundedBody(maxBytes: Int): ByteArray {
    require(maxBytes in 1 until Int.MAX_VALUE)
    if (contentLengthLong > maxBytes) throw HttpRequestBodyTooLarge()
    val bytes = runInterruptible(Dispatchers.IO) { inputStream.readNBytes(maxBytes + 1) }
    if (bytes.size > maxBytes) throw HttpRequestBodyTooLarge()
    return bytes
}

internal fun HttpServletResponse.writeText(
    text: String,
    status: HttpStatus = HttpStatus.OK,
    contentType: String = "text/plain",
) {
    this.status = status.value()
    this.contentType = contentType
    characterEncoding = "UTF-8"
    writer.write(text)
}

internal inline fun <reified T> HttpServletResponse.writeJson(
    value: T,
    status: HttpStatus = HttpStatus.OK,
    json: Json = httpJson,
) = writeText(json.encodeToString(value), status, "application/json")

internal fun HttpServletResponse.writeHttpError(status: HttpStatus, message: String) {
    setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
    writeJson(HttpError(message), status)
}

@Serializable
internal data class HttpError(val error: String)

@RestControllerAdvice
internal class GromozekaHttpExceptionHandler {
    @ExceptionHandler(HttpAuthenticationException::class)
    fun authentication(error: HttpAuthenticationException, response: HttpServletResponse) {
        error.challenge?.let { response.setHeader(HttpHeaders.WWW_AUTHENTICATE, it) }
        response.writeHttpError(error.status, error.publicMessage)
    }

    @ExceptionHandler(MissingAuthenticationException::class)
    fun missingAuthentication(response: HttpServletResponse) =
        response.writeHttpError(HttpStatus.UNAUTHORIZED, "Authentication required")

    @ExceptionHandler(ProjectAccessDeniedException::class)
    fun denied(response: HttpServletResponse) =
        response.writeHttpError(HttpStatus.FORBIDDEN, "Access denied")
}
