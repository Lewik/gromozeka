package com.gromozeka.infrastructure.ai.diagnostics

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

class AiTrafficDumpHttpClient(private val delegate: HttpClient, private val provider: String) : HttpClient() {
    override fun cookieHandler() = delegate.cookieHandler()
    override fun connectTimeout() = delegate.connectTimeout()
    override fun followRedirects() = delegate.followRedirects()
    override fun proxy() = delegate.proxy()
    override fun sslContext() = delegate.sslContext()
    override fun sslParameters() = delegate.sslParameters()
    override fun authenticator() = delegate.authenticator()
    override fun version() = delegate.version()
    override fun executor() = delegate.executor()
    override fun close() = delegate.close()
    override fun shutdown() = delegate.shutdown()
    override fun shutdownNow() = delegate.shutdownNow()
    override fun isTerminated() = delegate.isTerminated
    override fun awaitTermination(duration: java.time.Duration) = delegate.awaitTermination(duration)

    override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val trace = start(request) ?: return delegate.send(request, handler)
        return try { delegate.send(request.captured(trace), handler.captured(trace)) }
        catch (error: Throwable) { trace.failure(error); throw error }
    }

    override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> =
        sendAsync(request, handler, null)

    override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?): CompletableFuture<HttpResponse<T>> {
        val trace = start(request) ?: return delegate.sendAsync(request, handler, pushPromiseHandler)
        return try {
            delegate.sendAsync(request.captured(trace), handler.captured(trace), pushPromiseHandler).also { future ->
                future.whenComplete { _, error -> if (error != null) trace.failure(error) }
            }
        } catch (error: Throwable) { trace.failure(error); throw error }
    }

    private fun start(request: HttpRequest): AiTrafficRecording? = DangerousAiTrafficDump.open(provider, "http")?.also {
        it.headers("request", request.headers().map(), mapOf("url" to request.uri().toString(), "method" to request.method()))
    }

    private fun HttpRequest.captured(trace: AiTrafficRecording): HttpRequest {
        val publisher = bodyPublisher().orElse(null) ?: return this
        val captured = object : HttpRequest.BodyPublisher {
            override fun contentLength() = publisher.contentLength()
            override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) = publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) = subscriber.onSubscribe(subscription)
                override fun onNext(item: ByteBuffer) { trace.bytes("request", item); subscriber.onNext(item) }
                override fun onError(error: Throwable) { trace.failure(error); subscriber.onError(error) }
                override fun onComplete() { trace.event("request.complete"); subscriber.onComplete() }
            })
        }
        return HttpRequest.newBuilder(this) { _, _ -> true }.method(method(), captured).build()
    }

    private fun <T> HttpResponse.BodyHandler<T>.captured(trace: AiTrafficRecording) = HttpResponse.BodyHandler<T> { info ->
        trace.headers("response", info.headers().map(), mapOf("status" to info.statusCode().toString()))
        val subscriber = apply(info)
        object : HttpResponse.BodySubscriber<T> {
            override fun getBody() = subscriber.body
            override fun onSubscribe(subscription: Flow.Subscription) = subscriber.onSubscribe(subscription)
            override fun onNext(items: List<ByteBuffer>) {
                items.forEach { trace.bytes("response", it) }
                subscriber.onNext(items)
            }
            override fun onError(error: Throwable) { trace.failure(error); subscriber.onError(error) }
            override fun onComplete() { trace.event("response.complete"); subscriber.onComplete() }
        }
    }

    override fun newWebSocketBuilder(): WebSocket.Builder = object : WebSocket.Builder {
        private val builder = delegate.newWebSocketBuilder()
        private val headers = linkedMapOf<String, MutableList<String>>()
        override fun header(name: String, value: String) = apply {
            builder.header(name, value)
            headers.getOrPut(name) { mutableListOf() }.add(value)
        }
        override fun connectTimeout(timeout: java.time.Duration) = apply { builder.connectTimeout(timeout) }
        override fun subprotocols(mostPreferred: String, vararg lesserPreferred: String) = apply {
            builder.subprotocols(mostPreferred, *lesserPreferred)
        }
        override fun buildAsync(uri: java.net.URI, listener: WebSocket.Listener): CompletableFuture<WebSocket> {
            val trace = DangerousAiTrafficDump.open(provider, "websocket") ?: return builder.buildAsync(uri, listener)
            trace.headers("handshake.request", headers, mapOf("url" to uri.toString()))
            val sockets = java.util.concurrent.ConcurrentHashMap<WebSocket, CapturedWebSocket>()
            fun socket(raw: WebSocket) = sockets.computeIfAbsent(raw) { CapturedWebSocket(it, trace) }
            val text = StringBuilder()
            val captured = object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) {
                    trace.event("websocket.open")
                    listener.onOpen(socket(webSocket))
                }
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    trace.textFragment("receive.text.fragments.utf16be", data)
                    trace.event("receive.text.fragment", buildJsonObject { put("last", last) })
                    text.append(data)
                    if (last) { trace.text("receive.text", text.toString()); text.setLength(0) }
                    return listener.onText(socket(webSocket), data, last)
                }
                override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                    trace.bytes("receive.binary", data)
                    trace.event("receive.binary.fragment", buildJsonObject { put("last", last) })
                    return listener.onBinary(socket(webSocket), data, last)
                }
                override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
                    trace.bytes("receive.ping", message)
                    return listener.onPing(socket(webSocket), message)
                }
                override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
                    trace.bytes("receive.pong", message)
                    return listener.onPong(socket(webSocket), message)
                }
                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    trace.event("receive.close", buildJsonObject { put("status", statusCode); put("reason", reason) })
                    return listener.onClose(socket(webSocket), statusCode, reason)
                }
                override fun onError(webSocket: WebSocket, error: Throwable) {
                    trace.failure(error)
                    listener.onError(socket(webSocket), error)
                }
            }
            val pending = builder.buildAsync(uri, captured)
            val result = object : CompletableFuture<WebSocket>() {
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                    pending.cancel(mayInterruptIfRunning)
                    return super.cancel(mayInterruptIfRunning)
                }
            }
            pending.whenComplete { raw, error ->
                if (error == null) { if (!result.complete(socket(raw))) raw.abort() }
                else { trace.failure(error); result.completeExceptionally(error) }
            }
            return result
        }
    }
}

private class CapturedWebSocket(private val delegate: WebSocket, private val trace: AiTrafficRecording) : WebSocket {
    private val text = StringBuilder()
    @Synchronized
    override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
        trace.textFragment("send.text.fragments.utf16be", data)
        trace.event("send.text.fragment", buildJsonObject { put("last", last) })
        text.append(data)
        if (last) { trace.text("send.text", text.toString()); text.setLength(0) }
        return captured(delegate.sendText(data, last))
    }
    override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> {
        trace.bytes("send.binary", data)
        trace.event("send.binary.fragment", buildJsonObject { put("last", last) })
        return captured(delegate.sendBinary(data, last))
    }
    override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> {
        trace.bytes("send.ping", message)
        return captured(delegate.sendPing(message))
    }
    override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> {
        trace.bytes("send.pong", message)
        return captured(delegate.sendPong(message))
    }
    override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> {
        trace.event("send.close", buildJsonObject { put("status", statusCode); put("reason", reason) })
        return captured(delegate.sendClose(statusCode, reason))
    }
    override fun request(n: Long) = delegate.request(n)
    override fun getSubprotocol() = delegate.subprotocol
    override fun isInputClosed() = delegate.isInputClosed
    override fun isOutputClosed() = delegate.isOutputClosed
    override fun abort() { trace.event("websocket.abort"); delegate.abort() }

    private fun captured(pending: CompletableFuture<WebSocket>): CompletableFuture<WebSocket> {
        val result = object : CompletableFuture<WebSocket>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                pending.cancel(mayInterruptIfRunning)
                return super.cancel(mayInterruptIfRunning)
            }
        }
        pending.whenComplete { _, error ->
            if (error == null) result.complete(this)
            else { trace.failure(error); result.completeExceptionally(error) }
        }
        return result
    }
}
