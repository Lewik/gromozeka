package com.gromozeka.server

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpringWebResourcesTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `static files preserve content types caching validators and HEAD without a Wasm build`() = runBlocking<Unit> {
        val web = Files.createDirectory(root.resolve("web"))
        Files.writeString(web.resolve("index.html"), "<html>synthetic</html>")
        val hashName = "abcdef0123456789abcdef0123456789.wasm"
        val binary = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
        Files.write(web.resolve(hashName), binary)
        fixture(web).use { http ->
            val index = http.perform(get("/index.html").secureTestRequest())
            assertEquals(200, index.status)
            assertEquals("text/html", index.contentType)
            assertEquals("no-cache", index.getHeader(HttpHeaders.CACHE_CONTROL))
            val asset = http.perform(get("/$hashName").secureTestRequest())
            assertEquals(200, asset.status)
            assertEquals("application/wasm", asset.contentType)
            assertEquals("public, max-age=31536000, immutable", asset.getHeader(HttpHeaders.CACHE_CONTROL))
            assertContentEquals(binary, asset.contentAsByteArray)
            val etag = assertNotNull(asset.getHeader(HttpHeaders.ETAG))
            assertTrue(etag.startsWith('"') && etag.endsWith('"'))
            val unchanged = http.perform(get("/$hashName").secureTestRequest().header(HttpHeaders.IF_NONE_MATCH, etag))
            assertEquals(304, unchanged.status)
            assertTrue(unchanged.contentAsByteArray.isEmpty())
            val headersOnly = http.perform(head("/$hashName").secureTestRequest())
            assertEquals(200, headersOnly.status)
            assertEquals(binary.size.toString(), headersOnly.getHeader(HttpHeaders.CONTENT_LENGTH))
            assertTrue(headersOnly.contentAsByteArray.isEmpty())
            val landing = http.perform(get("/").secureTestRequest())
            assertEquals("/index.html", landing.forwardedUrl)
        }
    }

    @Test
    fun `precompressed asset negotiation varies encoding and keeps exact bytes`() = runBlocking<Unit> {
        val web = Files.createDirectory(root.resolve("compressed"))
        val name = "abcdef0123456789abcdef0123456789.js"
        val text = "const synthetic = 'שלום';".encodeToByteArray()
        Files.write(web.resolve(name), text)
        Files.newOutputStream(web.resolve("$name.gz")).use { stream -> GZIPOutputStream(stream).use { it.write(text) } }
        fixture(web).use { http ->
            val plain = http.perform(get("/$name").secureTestRequest())
            assertContentEquals(text, plain.contentAsByteArray)
            val gzip = http.perform(get("/$name").secureTestRequest().header(HttpHeaders.ACCEPT_ENCODING, "gzip"))
            assertEquals(200, gzip.status)
            assertEquals("gzip", gzip.getHeader(HttpHeaders.CONTENT_ENCODING))
            assertTrue(gzip.getHeaders(HttpHeaders.VARY).any { it.contains("Accept-Encoding", ignoreCase = true) })
            assertContentEquals(Files.readAllBytes(web.resolve("$name.gz")), gzip.contentAsByteArray)
            assertFalse(plain.getHeader(HttpHeaders.ETAG) == gzip.getHeader(HttpHeaders.ETAG))
        }
    }

    @Test
    fun `traversal and private API paths never fall through to public files`() = runBlocking<Unit> {
        val web = Files.createDirectory(root.resolve("public"))
        Files.writeString(root.resolve("secret.txt"), "PRIVATE_STATIC_SENTINEL")
        Files.createDirectories(web.resolve("api"))
        Files.writeString(web.resolve("api/private"), "PRIVATE_STATIC_SENTINEL")
        fixture(web).use { http ->
            for (path in listOf("/../secret.txt", "/%2e%2e/secret.txt", "/api/private", "/memory/status")) {
                val result = http.perform(get(path).secureTestRequest())
                assertTrue(result.status in 400..499, "$path -> ${result.status}")
                assertFalse(result.contentAsString.contains("PRIVATE_STATIC_SENTINEL"))
            }
            assertEquals(404, http.perform(get("/does-not-exist.js").secureTestRequest()).status)
        }
    }

    private fun fixture(web: Path) = SpringHttpTestFixture(GromozekaWebIndex(),
        settings = ServerHttpSettings(webRoot = web.toFile()), configurations = arrayOf(GromozekaWebRouting::class.java))
}
