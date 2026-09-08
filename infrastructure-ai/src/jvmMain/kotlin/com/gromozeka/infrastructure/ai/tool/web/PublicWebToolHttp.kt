package com.gromozeka.infrastructure.ai.tool.web

import java.net.InetAddress
import java.net.URI
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

internal fun requirePublicWebUrl(value: String): URI {
    require(value.length <= 8_192) { "Web URL is too long" }
    val uri = runCatching { URI(value) }.getOrNull()
        ?: throw IllegalArgumentException("A valid public HTTP or HTTPS URL is required")
    require(uri.scheme?.lowercase() in setOf("http", "https") && uri.rawUserInfo == null) {
        "Only public HTTP or HTTPS URLs without embedded credentials are allowed"
    }
    val host = uri.host?.removeSurrounding("[", "]")?.trimEnd('.')?.lowercase()
        ?: throw IllegalArgumentException("Web URL must have a valid host")
    require(uri.port == -1 || uri.port == 80 || uri.port == 443) { "Only standard public web ports are allowed" }
    if (':' in host || host.all { it.isDigit() || it == '.' }) {
        require(':' in host || (host.split('.').size == 4 && host.split('.').all { it.toIntOrNull() in 0..255 && (it == "0" || !it.startsWith('0')) })) {
            "Ambiguous numeric hosts are not allowed"
        }
        val address = InetAddress.getByName(host)
        val bytes = address.address.map { it.toInt() and 255 }
        val reserved = if (bytes.size == 4) {
            bytes[0] == 0 || bytes[0] >= 224 ||
                (bytes[0] == 100 && bytes[1] in 64..127) ||
                (bytes[0] == 198 && bytes[1] in 18..19)
        } else bytes[0] and 0xfe == 0xfc
        require(!reserved && !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress && !address.isMulticastAddress) { "Local and private network addresses are not allowed" }
    } else {
        require('.' in host && listOf("localhost", "local", "internal", "home", "lan", "test", "invalid").none { host == it || host.endsWith(".$it") }) {
            "A public web host is required"
        }
    }
    return uri
}

internal fun boundedWebBody(maxBytes: Int = 2 * 1024 * 1024): HttpResponse.BodyHandler<ByteArray> =
    HttpResponse.BodyHandler { BoundedWebBodySubscriber(maxBytes) }

internal class BoundedWebBodySubscriber(private val maxBytes: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val delegate = HttpResponse.BodySubscribers.ofByteArray()
    private lateinit var subscription: Flow.Subscription
    private var received = 0L
    private var terminated = false
    override fun getBody(): CompletionStage<ByteArray> = delegate.body
    override fun onSubscribe(value: Flow.Subscription) {
        subscription = value
        delegate.onSubscribe(value)
    }
    override fun onNext(items: List<ByteBuffer>) {
        if (terminated) return
        received += items.sumOf { it.remaining().toLong() }
        if (received > maxBytes) {
            terminated = true
            subscription.cancel()
            delegate.onError(IllegalStateException("Web response exceeds $maxBytes bytes"))
        } else delegate.onNext(items)
    }
    override fun onError(error: Throwable) { if (!terminated) { terminated = true; delegate.onError(error) } }
    override fun onComplete() { if (!terminated) { terminated = true; delegate.onComplete() } }
}
