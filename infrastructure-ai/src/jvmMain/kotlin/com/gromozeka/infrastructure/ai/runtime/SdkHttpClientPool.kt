package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.AiConnection
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Autowired
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.springframework.stereotype.Component
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Executor-local network resources; credentials and mutable call state belong to each invocation. */
@Component
class SdkHttpClientPool internal constructor(private val nanoTime: () -> Long) : AutoCloseable {
    @Autowired
    constructor() : this(System::nanoTime)

    private val lock = Any()
    private var closed = false
    private val entries = mutableMapOf<AiConnection.Id, Entry>()
    private val sessions = mutableSetOf<SdkHttpTransport>()
    private val dispatcher = Dispatcher().apply {
        // Harness admission owns concurrency; sharing a dispatcher must not introduce a second queue.
        maxRequests = Int.MAX_VALUE
        maxRequestsPerHost = Int.MAX_VALUE
    }

    internal fun openTransport(connection: AiConnection, baseUrl: String, deadlineNanos: Long): SdkHttpTransport {
        val settings = SdkHttpSupport.settings(connection)
        val proxy = SdkHttpSupport.proxy(settings, baseUrl)
        val caBundle = SdkHttpSupport.readCaBundle(settings)
        val key = Key(baseUrl, proxy, caBundle?.let { bytes ->
            java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        })
        return synchronized(lock) {
            check(!closed) { "AI HTTP client pool is closed" }
            val now = nanoTime()
            val idle = entries.filterValues { it.sessions.isEmpty() && now - it.lastReleasedNanos >= IDLE_NANOS }
            idle.forEach { (id, entry) -> entries.remove(id); retire(entry) }
            val current = entries[connection.id]
            val entry = if (current?.key == key) current else {
                val client = OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .writeTimeout(0, TimeUnit.MILLISECONDS)
                    .proxy(proxy)
                    .apply { SdkHttpSupport.tls(caBundle)?.let { sslSocketFactory(it.socketFactory, it.trustManager) } }
                    .build()
                Entry(key, client, now).also {
                    entries[connection.id] = it
                    current?.let(::retire)
                }
            }
            lateinit var transport: SdkHttpTransport
            transport = SdkHttpTransport(entry.client, deadlineNanos) { release(entry, transport) }
            check(entry.sessions.add(transport))
            check(sessions.add(transport))
            transport
        }
    }

    private fun retire(entry: Entry) {
        entry.retired = true
        if (entry.sessions.isEmpty()) entry.client.connectionPool.evictAll()
    }

    private fun release(entry: Entry, transport: SdkHttpTransport) = synchronized(lock) {
        check(sessions.remove(transport)) { "AI HTTP session was not registered" }
        check(entry.sessions.remove(transport)) { "AI HTTP session was not registered" }
        entry.lastReleasedNanos = nanoTime()
        if (entry.retired && entry.sessions.isEmpty()) entry.client.connectionPool.evictAll()
    }

    @PreDestroy
    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.forEach(::retire)
            entries.clear()
            sessions.toList()
        }
        try {
            active.forEach(SdkHttpTransport::close)
        } finally {
            dispatcher.executorService.shutdown()
        }
    }

    private data class Key(val baseUrl: String, val proxy: Proxy?, val caFingerprint: String?)
    private class Entry(val key: Key, val client: OkHttpClient, var lastReleasedNanos: Long) {
        val sessions = mutableSetOf<SdkHttpTransport>()
        var retired = false
    }
    private companion object { val IDLE_NANOS = TimeUnit.MINUTES.toNanos(5) }
}
