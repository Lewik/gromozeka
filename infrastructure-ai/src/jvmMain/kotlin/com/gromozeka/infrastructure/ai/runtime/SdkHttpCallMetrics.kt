package com.gromozeka.infrastructure.ai.runtime

import klog.KLoggers
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Numeric transport measurements only: never log URLs, headers, bodies or exception text. */
internal class SdkHttpCallMetrics : EventListener() {
    private var started = 0L
    private var connectStarted = 0L
    private var tlsStarted = 0L
    private var connectNanos = 0L
    private var tlsNanos = 0L
    private var connects = 0
    private var reused: Boolean? = null
    private var requestBytes = 0L
    private var responseBytes = 0L

    override fun callStart(call: Call) { started = System.nanoTime() }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connects++
        connectStarted = System.nanoTime()
    }
    override fun secureConnectStart(call: Call) { tlsStarted = System.nanoTime() }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsNanos += System.nanoTime() - tlsStarted
        tlsStarted = 0
    }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        finishConnect()
    }
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        finishConnect()
    }
    override fun connectionAcquired(call: Call, connection: Connection) { reused = connects == 0 }
    override fun requestBodyEnd(call: Call, byteCount: Long) { requestBytes += byteCount }
    override fun responseBodyEnd(call: Call, byteCount: Long) { responseBytes += byteCount }
    override fun callEnd(call: Call) = report("completed")
    override fun callFailed(call: Call, ioe: IOException) = report("failed")

    private fun finishConnect() {
        val now = System.nanoTime()
        connectNanos += now - connectStarted
        if (tlsStarted != 0L) { tlsNanos += now - tlsStarted; tlsStarted = 0 }
        connectStarted = 0
    }

    private fun report(outcome: String) {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        val thread = Thread.currentThread()
        val originalName = thread.name
        try {
            // OkHttp embeds the request URL in this thread name; logging layouts may print it.
            thread.name = "sdk-http-metrics"
            KLoggers.logger(this).info {
                "SDK HTTP outcome=$outcome elapsed_ms=$elapsed connects=$connects reused=$reused " +
                    "connect_ms=${TimeUnit.NANOSECONDS.toMillis(connectNanos)} tls_ms=${TimeUnit.NANOSECONDS.toMillis(tlsNanos)} " +
                    "request_body_bytes=$requestBytes response_body_bytes=$responseBytes"
            }
        } finally { thread.name = originalName }
    }
}
