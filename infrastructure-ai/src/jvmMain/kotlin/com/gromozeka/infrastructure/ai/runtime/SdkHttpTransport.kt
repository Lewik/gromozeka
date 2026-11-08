package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.AiHttpSettings
import com.gromozeka.infrastructure.ai.diagnostics.AiTrafficDumpInterceptor
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Owns native calls through body parsing: cancelling an SDK's derived future alone does not do that. */
internal class SdkHttpTransport(settings: AiHttpSettings, baseUrl: String, private val deadlineNanos: Long) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val calls = mutableSetOf<Call>()
    private val responses = mutableSetOf<NativeResponse>()
    private var current = Attempt()
    private var count = 0
    private class ExchangeGuard { val exchanges = java.util.concurrent.atomic.AtomicInteger() }
    private val client = OkHttpClient.Builder()
        .addNetworkInterceptor(AiTrafficDumpInterceptor("sdk"))
        .addNetworkInterceptor { chain ->
            val guard = requireNotNull(chain.request().tag(ExchangeGuard::class.java))
            // OkHttp can repeat a bodyless GET after 503 + Retry-After: 0 even with connection retries off.
            if (guard.exchanges.incrementAndGet() != 1) throw HttpFollowUpPreventedException()
            chain.proceed(chain.request()).also { response ->
                synchronized(lock) { current = current.copy(status = response.code,
                    retryAfterMillis = parseRetryAfter(response.header("Retry-After"))) }
            }
        }
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .proxy(SdkHttpSupport.proxy(settings, baseUrl))
        .apply { SdkHttpSupport.tls(settings)?.let { sslSocketFactory(it.socketFactory, it.trustManager) } }
        .build()

    data class Attempt(val started: Boolean = false, val status: Int? = null, val bodyComplete: Boolean = false,
        val retryAfterMillis: Long? = null)
    val attempts: Int get() = synchronized(lock) { count }
    fun snapshot(): Attempt = synchronized(lock) { current }
    fun beginAttempt() = synchronized(lock) {
        check(calls.isEmpty() && responses.isEmpty()) { "Previous AI HTTP attempt has not closed" }
        current = Attempt()
    }

    fun execute(request: Request, closeRequestBody: () -> Unit): CompletableFuture<NativeResponse> {
        val future = CompletableFuture<NativeResponse>()
        synchronized(lock) {
            if (closed || deadlineNanos - System.nanoTime() <= 0) {
                closeRequestBody()
                future.completeExceptionally(IOException("AI HTTP call is closed or its deadline expired"))
                return future
            }
            check(!current.started) { "SDK attempted more than one HTTP exchange in a controlled attempt" }
            val call = client.newCall(request.newBuilder().tag(ExchangeGuard::class.java, ExchangeGuard()).build())
            call.timeout().timeout((deadlineNanos - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            calls.add(call)
            current = Attempt(started = true)
            count++
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    closeRequestBody()
                    synchronized(lock) { calls.remove(call) }
                    future.completeExceptionally(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    closeRequestBody()
                    val native = NativeResponse(call, response)
                    val accepted = synchronized(lock) {
                        current = current.copy(status = response.code, bodyComplete = response.body?.contentLength() == 0L,
                            retryAfterMillis = parseRetryAfter(response.header("Retry-After")))
                        if (closed) false else { responses.add(native); true }
                    }
                    if (!accepted || !future.complete(native)) native.close()
                }
            })
        }
        return future
    }

    inner class NativeResponse(private val call: Call, val response: Response) : AutoCloseable {
        private var released = false
        private val length = response.body?.contentLength() ?: 0L
        private var read = 0L
        val body: InputStream = object : FilterInputStream(requireNotNull(response.body).byteStream()) {
            override fun read(): Int = super.read().also { received(if (it < 0) -1 else 1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                `in`.read(buffer, offset, length).also(::received)
            override fun close() = this@NativeResponse.close()
        }
        private fun received(bytes: Int) {
            synchronized(lock) {
                if (bytes > 0) read += bytes
                if (bytes < 0 || (length >= 0 && read == length)) current = current.copy(bodyComplete = true)
            }
        }
        override fun close() {
            synchronized(lock) {
                if (released) return
                released = true
                responses.remove(this)
                calls.remove(call)
            }
            response.close()
        }
    }

    /** Also cancels a call whose response headers arrived but whose body is still being parsed. */
    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            calls.toList() to responses.toList()
        }
        active.first.forEach(Call::cancel)
        active.second.forEach(NativeResponse::close)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

internal class HttpFollowUpPreventedException : IOException("Automatic HTTP follow-up was prevented")

internal fun parseRetryAfter(value: String?, now: Instant = Instant.now()): Long? {
    if (value == null) return null
    val text = value.trim()
    if (text.isNotEmpty() && text.all { it in '0'..'9' }) {
        val seconds = text.toLongOrNull() ?: return Long.MAX_VALUE
        return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
    }
    return try {
        java.time.Duration.between(now, ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
            .toMillis().coerceAtLeast(0)
    } catch (_: java.time.DateTimeException) { null } catch (_: ArithmeticException) { Long.MAX_VALUE }
}

private fun nativeRequest(method: String, baseUrl: String, path: List<String>, headers: Map<String, List<String>>,
    query: Map<String, List<String>>, contentType: String?, contentLength: Long, writeBody: ((OutputStream) -> Unit)?): Request {
    val url = baseUrl.toHttpUrl().newBuilder().apply {
        path.forEach(::addPathSegment)
        query.forEach { (name, values) -> values.forEach { addQueryParameter(name, it) } }
    }.build()
    val body = writeBody?.let { write -> object : RequestBody() {
        override fun contentType() = contentType?.toMediaTypeOrNull()
        override fun contentLength() = contentLength
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = write(sink.outputStream())
    } }
    return Request.Builder().url(url).method(method, body).apply {
        headers.forEach { (name, values) -> values.forEach { addHeader(name, it) } }
    }.build()
}

internal class OpenAiControlledHttpClient(private val transport: SdkHttpTransport) : com.openai.core.http.HttpClient {
    override fun execute(request: com.openai.core.http.HttpRequest, requestOptions: com.openai.core.RequestOptions) =
        executeAsync(request, requestOptions).join()
    override fun executeAsync(request: com.openai.core.http.HttpRequest, requestOptions: com.openai.core.RequestOptions):
        CompletableFuture<com.openai.core.http.HttpResponse> {
        val body = request.body
        val native = nativeRequest(request.method.name, request.baseUrl, request.pathSegments,
            request.headers.names().associateWith { request.headers.values(it) },
            request.queryParams.keys().associateWith { request.queryParams.values(it) },
            body?.contentType(), body?.contentLength() ?: 0, body?.let { it::writeTo })
        return transport.execute(native) { body?.close() }.thenApply { response ->
            object : com.openai.core.http.HttpResponse {
                override fun statusCode() = response.response.code
                override fun headers() = com.openai.core.http.Headers.builder().apply {
                    response.response.headers.forEach { (name, value) -> put(name, value) }
                }.build()
                override fun body() = response.body
                override fun close() = response.close()
            }
        }
    }
    override fun close() = transport.close()
}

internal class AnthropicControlledHttpClient(private val transport: SdkHttpTransport,
    private val backend: com.anthropic.backends.AnthropicBackend) : com.anthropic.core.http.HttpClient {
    override fun execute(request: com.anthropic.core.http.HttpRequest, requestOptions: com.anthropic.core.RequestOptions) =
        executeAsync(request, requestOptions).join()
    override fun executeAsync(request: com.anthropic.core.http.HttpRequest, requestOptions: com.anthropic.core.RequestOptions):
        CompletableFuture<com.anthropic.core.http.HttpResponse> {
        val prepared = backend.authorizeRequest(backend.prepareRequest(request))
        val body = prepared.body
        val native = nativeRequest(prepared.method.name, prepared.baseUrl ?: backend.baseUrl(), prepared.pathSegments,
            prepared.headers.names().associateWith { prepared.headers.values(it) },
            prepared.queryParams.keys().associateWith { prepared.queryParams.values(it) },
            body?.contentType(), body?.contentLength() ?: 0, body?.let { it::writeTo })
        return transport.execute(native) { body?.close() }.thenApply { response ->
            object : com.anthropic.core.http.HttpResponse {
                override fun statusCode() = response.response.code
                override fun headers() = com.anthropic.core.http.Headers.builder().apply {
                    response.response.headers.forEach { (name, value) -> put(name, value) }
                }.build()
                override fun body() = response.body
                override fun close() = response.close()
            }
        }
    }
    override fun close() { transport.close(); backend.close() }
}
