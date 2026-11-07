package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.SecretRef
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class AiHttpSettingsTest {
    @Test
    fun `all three SDK connections preserve HTTP settings and one secret source through JSON`() {
        val settings = AiHttpSettings(AiHttpSettings.Authentication.BEARER, "/executor/ca.pem", "http://proxy.example:8080",
            listOf(".example", "localhost:9443", "[::1]"), mapOf("User-Agent" to "Gromozeka-test", "x-tfy-strict-openai" to "false"))
        val source = SecretRef.File("/executor/token")
        val connections = listOf<AiConnection>(
            AiConnection.OpenAiApi(AiConnection.Id("responses"), "Responses", baseUrl = "https://gateway/api/llm", apiKey = source, httpSettings = settings),
            AiConnection.OpenAiCompatible(AiConnection.Id("chat"), "Chat", baseUrl = "https://gateway/api/llm", apiKey = source, httpSettings = settings),
            AiConnection.AnthropicApi(AiConnection.Id("messages"), "Messages", baseUrl = "https://gateway/api/llm", apiKey = source, httpSettings = settings),
        )
        val json = Json { encodeDefaults = true }
        for (connection in connections) assertEquals(connection, json.decodeFromString<AiConnection>(json.encodeToString(connection)))
        val old = Json.decodeFromString<AiConnection>("""{"connectionKind":"anthropic_api","id":"old","displayName":"Old"}""")
        assertEquals(AiHttpSettings(), (old as AiConnection.AnthropicApi).httpSettings)
    }

    @Test
    fun `file secret metadata is explicit and inline debug representations never expose the value`() {
        val state = AiCatalogSecretState(AiCatalogSecretSlot.ConnectionApiKey(AiConnection.Id("one")), AiCatalogSecretState.Source.FILE,
            filePath = "/executor/token")
        assertEquals(state, Json.decodeFromString<AiCatalogSecretState>(Json.encodeToString(state)))
        assertFailsWith<IllegalArgumentException> { state.copy(filePath = null) }
        assertFailsWith<IllegalArgumentException> { state.copy(environmentVariableName = "TOKEN") }
        assertFalse(SecretRef.Inline("private-marker").toString().contains("private-marker"))
        assertEquals(SecretRef.File("/executor/token"), Json.decodeFromString<SecretRef>("""{"secretType":"file","path":"/executor/token"}"""))
        assertFailsWith<IllegalArgumentException> { SecretRef.File("\n") }
    }

    @Test
    fun `authentication and transport headers cannot bypass secret handling or inject headers`() {
        for (name in listOf("Authorization", "authorization", "AuThOrIzAtIoN", "X-API-Key", "api-key", "Proxy-Authorization", "Cookie", "Host", "Content-Length")) {
            val error = assertFailsWith<IllegalArgumentException> { AiHttpSettings(extraHeaders = mapOf(name to "private-marker")) }
            assertFalse(error.toString().contains("private-marker"))
        }
        assertFailsWith<IllegalArgumentException> { AiHttpSettings(extraHeaders = mapOf("X-Test" to "one\r\nAuthorization: private-marker")) }
        assertFailsWith<IllegalArgumentException> { AiHttpSettings(extraHeaders = mapOf("X-Test" to "a", "x-test" to "b")) }
        assertFailsWith<IllegalArgumentException> { AiHttpSettings(extraHeaders = mapOf("Bad Header" to "a")) }
        AiHttpSettings(extraHeaders = mapOf("user-agent" to "Custom", "anthropic-beta" to "test-beta"))
    }

    @Test
    fun `TrueFoundry metadata accepts only string values within the documented character limit`() {
        AiHttpSettings(extraHeaders = mapOf("X-TFY-METADATA" to """{"name":"${"x".repeat(128)}"}"""))
        AiHttpSettings(extraHeaders = mapOf("x-tfy-metadata" to ("{\"name\":\"" + "\\uD83D\\uDE00".repeat(128) + "\"}")))
        for (value in listOf("[]", "{\"name\":1}", "{\"name\":null}", "{\"name\":{}}", "{private-marker", "{\"name\":\"${"x".repeat(129)}\"}")) {
            val error = assertFailsWith<IllegalArgumentException> { AiHttpSettings(extraHeaders = mapOf("X-TFY-METADATA" to value)) }
            assertFalse(error.toString().contains("private-marker"))
        }
    }

    @Test
    fun `noProxy supports exact hosts suffixes IP literals and optional ports without substring matches`() {
        val settings = AiHttpSettings(noProxy = listOf("EXACT.example", ".corp.example", "localhost:9443", "127.0.0.1", "[::1]:8443"))
        for ((host, port) in listOf("exact.example" to 443, "corp.example" to 443, "a.corp.example" to 443,
            "localhost" to 9443, "127.0.0.1" to 80, "[::1]" to 8443, "::1" to 8443)) assertTrue(settings.bypassesProxy(host, port))
        for ((host, port) in listOf("evilcorp.example" to 443, "corp.example.evil" to 443, "a.exact.example" to 443,
            "localhost" to 443, "::1" to 443)) assertFalse(settings.bypassesProxy(host, port))
        assertTrue(AiHttpSettings(noProxy = listOf("*")).bypassesProxy("any.example", 443))
        for (rule in listOf("", "has space", "*.example", "10.0.0.0/8", "https://host", "host:bad", "host:70000", "[::1")) {
            assertFailsWith<IllegalArgumentException> { AiHttpSettings(noProxy = listOf(rule)) }
        }
    }

    @Test
    fun `explicit endpoint and proxy cannot hide credentials in a URL`() {
        for (value in listOf("https://token@gateway/api", "https://gateway/api?token=private", "https://gateway/api#token", "ftp://gateway/api", "")) {
            assertFailsWith<IllegalArgumentException> { AiConnection.OpenAiCompatible(AiConnection.Id("test"), "Test", baseUrl = value) }
        }
        for (value in listOf("http://user:password@proxy:8080", "https://proxy:8080", "http://proxy:70000", "http://proxy/path")) {
            assertFailsWith<IllegalArgumentException> { AiHttpSettings(proxyUrl = value) }
        }
    }
}
