package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.ai.AiHttpSettings
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OpenAiLiveTranscriptionConfigurationTest {
    @Test
    fun `auto omits languages instead of sending an empty list or inheriting dictation settings`() {
        val input = configuration(emptyList()).sessionUpdate()["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
        val transcription = input["transcription"]!!.jsonObject
        assertEquals("gpt-live-transcribe", transcription["model"]!!.jsonPrimitive.content)
        assertFalse("language" in transcription)
        assertFalse("languages" in transcription)
        assertEquals(JsonNull, input["turn_detection"])
        assertEquals("24000", input["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content)
    }

    @Test
    fun `multiple expected languages are sent as hints without a singular language field`() {
        val input = configuration(listOf("he", "en", "ru")).sessionUpdate()["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
        val transcription = input["transcription"]!!.jsonObject
        assertEquals(listOf("he", "en", "ru"), transcription["languages"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("language" in transcription)
        assertFalse("prompt" in transcription)
    }

    @Test
    fun `gpt transcribe uses semantic low without streaming delay and keeps language hints`() {
        for (languages in listOf(emptyList(), listOf("he", "en", "ru"))) {
            val configured = OpenAiLiveTranscriptionConfiguration(
                baseUrl = "https://api.openai.com/v1/",
                apiKey = "synthetic-test-key",
                model = "gpt-transcribe",
                languages = languages,
            )
            val input = configured.sessionUpdate()["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
            val transcription = input["transcription"]!!.jsonObject
            assertEquals("gpt-transcribe", transcription["model"]!!.jsonPrimitive.content)
            assertFalse("delay" in transcription)
            assertFalse("language" in transcription)
            if (languages.isEmpty()) assertFalse("languages" in transcription)
            else assertEquals(languages, transcription["languages"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("semantic_vad", input["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("low", input["turn_detection"]!!.jsonObject["eagerness"]!!.jsonPrimitive.content)
            val stopping = configured.sessionUpdate(disableTurnDetection = true)["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
            assertEquals(JsonNull, stopping["turn_detection"])
        }
    }

    @Test
    fun `transcription endpoint does not create a voice assistant conversation`() {
        assertEquals("wss://api.openai.com/v1/realtime?intent=transcription", configuration(emptyList()).endpoint().toString())
    }

    @Test
    fun `invalid internal language hints fail before a network request`() {
        assertFailsWith<IllegalArgumentException> { configuration(listOf("he", "he")) }
        assertFailsWith<IllegalArgumentException> { configuration(listOf("Hebrew")) }
    }

    @Test
    fun `big endian microphone PCM is explicitly converted to little endian 24 kHz`() {
        val input = byteArrayOf(0, 0, 0x2e, 0xe0.toByte(), 0xd1.toByte(), 0x20)
        val expected = byteArrayOf(0, 0, 0x40, 0x1f, 0xa0.toByte(), 0x0f, 0x20, 0xd1.toByte())
        kotlin.test.assertContentEquals(expected, resamplePcm16To24kLittleEndian(input, 16_000, OpenAiRealtimePcmByteOrder.BIG_ENDIAN))
    }

    @Test
    fun `configured headers and proxy are not bypassed by the live transport`() {
        val settings = AiHttpSettings(proxyUrl = "http://proxy.invalid:3128", extraHeaders = mapOf("X-Fixture" to "configured"))
        val configured = configuration(emptyList(), settings)
        assertEquals("configured", configured.requestHeaders("safety-id")["X-Fixture"])
        val selected = configured.httpClient().proxy().get().select(URI("https://api.openai.com/v1")).single()
        assertEquals(Proxy.Type.HTTP, selected.type())
        assertEquals("proxy.invalid", (selected.address() as InetSocketAddress).hostString)
        val bypass = configuration(emptyList(), settings.copy(noProxy = listOf("api.openai.com")))
        assertEquals(Proxy.NO_PROXY, bypass.httpClient().proxy().get().select(URI("https://api.openai.com/v1")).single())
    }

    @Test
    fun `invalid configured CA fails instead of silently using only system roots`() {
        val directory = Files.createTempDirectory("live-ca-fixture-")
        try {
            val configured = configuration(emptyList(), AiHttpSettings(caBundlePath = directory.resolve("missing.pem").toString()))
            assertFailsWith<IllegalStateException> { configured.httpClient() }
        } finally {
            Files.delete(directory)
        }
    }

    private fun configuration(languages: List<String>, httpSettings: AiHttpSettings = AiHttpSettings()) = OpenAiLiveTranscriptionConfiguration(
        baseUrl = "https://api.openai.com/v1/",
        apiKey = "synthetic-test-key",
        model = "gpt-live-transcribe",
        languages = languages,
        httpSettings = httpSettings,
    )
}
