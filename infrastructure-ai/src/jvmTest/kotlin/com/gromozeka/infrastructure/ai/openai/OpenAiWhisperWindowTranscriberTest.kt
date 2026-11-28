package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.LiveTranscriptionTurnDetection
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.RoutingLiveTranscriptionProvider
import com.gromozeka.infrastructure.ai.runtime.SdkHttpClientPool
import com.gromozeka.infrastructure.ai.runtime.TestAiAdmission
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiWhisperWindowTranscriberTest {
    private val httpClients = SdkHttpClientPool()

    @AfterTest
    fun closeHttpClients() = httpClients.close()

    @Test
    fun `production SDK sends verbose word timestamps and maps them to absolute audio time`() = runBlocking {
        val body = AtomicReference<ByteArray>()
        val authorization = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/audio/transcriptions") { exchange ->
                body.set(exchange.requestBody.readAllBytes())
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                val response = """{
                    "task":"transcribe",
                    "language":"hebrew",
                    "duration":1.0,
                    "text":" שלום, עולם.",
                    "words":[
                        {"word":"שלום","start":0.0,"end":0.4},
                        {"word":"עולם","start":0.5,"end":0.9}
                    ],
                    "usage":{"type":"duration","seconds":1}
                }""".trimIndent().encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            val connection = AiConnection.OpenAiApi(
                id = AiConnection.Id("whisper-test"),
                displayName = "Whisper test",
                enabled = true,
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                apiKey = SecretRef.Inline("synthetic-test-key"),
            )
            val configuration = AiModelConfiguration(
                id = AiModelConfiguration.Id("whisper-test"),
                connectionId = connection.id,
                providerModelId = "whisper-1",
                displayName = "Whisper test",
            )
            val runtime = ResolvedAiRuntime(
                connection,
                configuration,
                AiModelSpec(
                    id = "whisper-1",
                    provider = AiProvider.OPENAI,
                    capabilities = setOf(AiModelCapability.SPEECH_TO_TEXT, AiModelCapability.REALTIME_TRANSCRIPTION),
                ),
            )
            val admission = TestAiAdmission()
            val result = OpenAiWhisperWindowTranscriber(
                runtime,
                OpenAiSdkClientFactory(TestSettingsProvider, httpClients),
                admission,
                listOf("he"),
            ).transcribe(startSample = 32_000, pcmLittleEndian = ByteArray(32_000))

            assertEquals(" שלום, עולם.", result.text)
            assertEquals(listOf(32_000L to 38_400L, 40_000L to 46_400L),
                result.words.map { it.startSample to it.endSample })
            assertEquals("Bearer synthetic-test-key", authorization.get())
            val multipart = body.get().toString(StandardCharsets.ISO_8859_1)
            assertTrue("RIFF" in multipart)
            assertTrue("whisper-1" in multipart)
            assertTrue("verbose_json" in multipart)
            assertTrue("timestamp_granularities" in multipart)
            assertTrue("word" in multipart)
            assertTrue("language" in multipart && "he" in multipart)
            assertTrue(admission.active.isEmpty())
            assertEquals(listOf(AiAdmissionOutcome.SUCCEEDED), admission.completed.map { it.second })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `declared speech model aliases use windowed Live without opening a native socket`() = runBlocking<Unit> {
        val connection = AiConnection.OpenAiApi(
            id = AiConnection.Id("work-gateway"),
            displayName = "Work gateway",
            enabled = true,
            apiKey = SecretRef.Inline("synthetic-test-key"),
        )
        val configuration = AiModelConfiguration(
            id = AiModelConfiguration.Id("work-whisper"),
            connectionId = connection.id,
            providerModelId = "approved-whisper-deployment",
            displayName = "Approved Whisper deployment",
        )
        val runtime = ResolvedAiRuntime(
            connection,
            configuration,
            AiModelSpec(
                id = configuration.providerModelId,
                provider = AiProvider.OPENAI,
                capabilities = setOf(AiModelCapability.SPEECH_TO_TEXT, AiModelCapability.REALTIME_TRANSCRIPTION),
            ),
        )
        val provider = RoutingLiveTranscriptionProvider(
            RuntimeConfiguration(runtime),
            listOf(OpenAiLiveTranscriptionProvider(OpenAiSdkClientFactory(TestSettingsProvider, httpClients), TestAiAdmission())),
        )

        val session = provider.open("user", AiRuntimeSelection(configuration.id), listOf("he", "en", "ru"), false)
        assertEquals(LiveTranscriptionTurnDetection.PROVIDER, session.turnDetection)
        session.close(AiAdmissionOutcome.CANCELLED)
        assertFailsWith<IllegalArgumentException> {
            provider.open("user", AiRuntimeSelection(configuration.id), listOf("he"), true)
        }
    }

    private class RuntimeConfiguration(private val runtime: ResolvedAiRuntime) : AiConfigurationProvider {
        override val snapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(null)
        override val snapshot: AiCatalogSnapshot get() = error("Catalog snapshot is outside this test")

        override fun runtimeSelectionFor(purpose: AiRuntimeAssignment.Purpose): AiRuntimeSelection =
            AiRuntimeSelection(runtime.modelConfiguration.id)

        override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime {
            assertEquals(runtime.modelConfiguration.id, selection.modelConfigurationId)
            return runtime
        }
    }

    private object TestSettingsProvider : SettingsProvider {
        override val userProfile: UserProfile = UserProfile()
        override val userDeviceSettings: UserDeviceSettings = UserDeviceSettings.Desktop()
        override val mode: AppMode = AppMode.TEST
        override val homeDirectory: String = System.getProperty("java.io.tmpdir")

        override fun resolveSecret(secretRef: SecretRef?): String? = when (secretRef) {
            is SecretRef.Inline -> secretRef.value
            is SecretRef.EnvironmentVariable -> System.getenv(secretRef.name)
            is SecretRef.File -> File(secretRef.path).takeIf(File::isFile)?.readText()
            null -> null
        }
    }
}
