package com.gromozeka.infrastructure.ai.diagnostics

import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSource
import okio.buffer
import okio.sink

internal class AiTrafficDumpInterceptor(private val provider: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val trace = DangerousAiTrafficDump.open(provider, "http") ?: return chain.proceed(original)
        trace.headers("request", original.headers.toMultimap(), mapOf("url" to original.url.toString(), "method" to original.method))
        val body = original.body?.let { source -> object : RequestBody() {
            override fun contentType() = source.contentType()
            override fun contentLength() = source.contentLength()
            override fun isOneShot() = source.isOneShot()
            override fun isDuplex() = source.isDuplex()
            override fun writeTo(sink: BufferedSink) {
                val captured = trace.output("request", sink.outputStream()).sink().buffer()
                source.writeTo(captured)
                captured.flush()
                trace.event("request.complete")
            }
        } }
        try {
            val response = chain.proceed(original.newBuilder().method(original.method, body).build())
            trace.headers("response", response.headers.toMultimap(), mapOf("status" to response.code.toString()))
            val responseBody = response.body ?: return response
            val captured = object : ForwardingSource(responseBody.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val offset = sink.size
                    val count = try {
                        super.read(sink, byteCount)
                    } catch (error: Exception) {
                        trace.failure(error)
                        throw error
                    }
                    if (count > 0) {
                        val copy = Buffer()
                        sink.copyTo(copy, offset, count)
                        trace.bytes("response", copy.readByteArray())
                    } else if (count < 0) trace.event("response.complete")
                    return count
                }
                override fun close() { try { super.close() } finally { trace.event("response.closed") } }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = responseBody.contentType()
                override fun contentLength() = responseBody.contentLength()
                override fun source() = captured
            }).build()
        } catch (error: Throwable) { trace.failure(error); throw error }
    }
}
