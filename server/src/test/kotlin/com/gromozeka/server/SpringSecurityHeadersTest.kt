package com.gromozeka.server

import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpringSecurityHeadersTest {
    @Test
    fun `security headers are written before suspend controllers can touch the response`() = runBlocking {
        val controller = HeaderProbe()
        SpringHttpTestFixture(controller).use { http ->
            repeat(12) {
                val response = http.perform(get("/auth/header-probe").secureTestRequest())
                assertEquals(200, response.status)
                assertEquals("ready", response.contentAsString)
                assertEquals("no-store", response.getHeader("Cache-Control"), "Controller still owns the cache policy")
                expected.forEach { (name, value) -> assertEquals(listOf(value), response.getHeaders(name).toList(), name) }
            }
        }
        assertEquals(24, controller.observations.size)
        assertTrue(controller.observations.all { it == expected }, "Headers must exist before initial controller work and after suspension")
    }

    @RestController
    internal class HeaderProbe {
        val observations = CopyOnWriteArrayList<Map<String, String?>>()

        @GetMapping("/auth/header-probe")
        suspend fun probe(response: HttpServletResponse) {
            observations += expected.keys.associateWith(response::getHeader)
            withContext(Dispatchers.Default) {
                observations += expected.keys.associateWith(response::getHeader)
                response.setHeader("Cache-Control", "no-store")
                response.writeText("ready")
            }
        }
    }

    private companion object {
        val expected = mapOf(
            "X-Content-Type-Options" to "nosniff",
            "X-XSS-Protection" to "0",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
            "Permissions-Policy" to "camera=(self), microphone=(self), geolocation=(self), display-capture=(self)",
        )
    }
}
