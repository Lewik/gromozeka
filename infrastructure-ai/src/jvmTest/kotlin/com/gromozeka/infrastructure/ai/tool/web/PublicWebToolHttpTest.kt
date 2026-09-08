package com.gromozeka.infrastructure.ai.tool.web

import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import kotlin.test.*

class PublicWebToolHttpTest {
    @Test fun `reader rejects local paths credentials private addresses and ambiguous hosts`() {
        listOf("file:///etc/passwd", "ftp://example.com/file", "https://user:pass@example.com/", "http://localhost/",
            "http://localhost./", "http://printer.lan/", "http://127.0.0.1/", "http://169.254.169.254/",
            "http://10.1.2.3/", "http://192.168.1.1/", "http://172.16.2.3/", "http://100.64.1.1/", "http://[::1]/",
            "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/", "http://2130706433/", "http://127.1/",
            "http://0177.0.0.1/", "https://example.com:1234/", "not a URL").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { requirePublicWebUrl(value) }
        }
        listOf("https://example.com/path?q=value", "http://example.org/", "https://8.8.8.8/", "https://[2606:4700::1111]/").forEach {
            assertEquals(it, requirePublicWebUrl(it).toString())
        }
    }

    @Test fun `body limit cancels oversized responses without retaining extra chunks`() {
        var cancelled = false
        val subscription = object : Flow.Subscription {
            override fun request(n: Long) = Unit
            override fun cancel() { cancelled = true }
        }
        val bounded = BoundedWebBodySubscriber(4)
        bounded.onSubscribe(subscription)
        bounded.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        bounded.onNext(listOf(ByteBuffer.wrap(byteArrayOf(4, 5))))
        bounded.onComplete()
        assertTrue(cancelled)
        assertFailsWith<ExecutionException> { bounded.body.toCompletableFuture().get() }
        val valid = BoundedWebBodySubscriber(4)
        valid.onSubscribe(subscription)
        valid.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))))
        valid.onComplete()
        assertContentEquals(byteArrayOf(1, 2, 3, 4), valid.body.toCompletableFuture().get())
    }
}
