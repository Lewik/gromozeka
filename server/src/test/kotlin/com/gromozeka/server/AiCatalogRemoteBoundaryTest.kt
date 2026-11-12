package com.gromozeka.server

import com.gromozeka.application.service.RuntimeCatalogTemplateApplicationService
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.ai.AiCatalogSecretSlot
import com.gromozeka.domain.model.ai.AiCatalogSecretState
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.secretStates
import com.gromozeka.remote.protocol.AiCatalogResponse
import com.gromozeka.remote.protocol.GromozekaServerEnvelope
import com.gromozeka.remote.protocol.RemoteAiCatalogSnapshot
import com.gromozeka.remote.protocol.RemoteProtocolCodec
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiCatalogRemoteBoundaryTest {
    @Test
    fun `remote catalog preserves HTTP settings and file metadata without reading executor files`() {
        val seed = RuntimeCatalogTemplateApplicationService().createSeed().aiCatalog
        val settings = com.gromozeka.domain.model.ai.AiHttpSettings(
            authentication = com.gromozeka.domain.model.ai.AiHttpSettings.Authentication.BEARER,
            caBundlePath = "/worker-only/ca.pem", proxyUrl = "http://proxy.example:8080", noProxy = listOf(".internal"),
            extraHeaders = mapOf("User-Agent" to "Catalog-fixture"), maxAttempts = 4)
        val reference = SecretRef.File("/worker-only/token")
        val catalog = seed.copy(connections = seed.connections.map {
            if (it is AiConnection.OpenAiApi) it.copy(baseUrl = "https://gateway.example/api/llm", apiKey = reference,
                httpSettings = settings, executionTarget = com.gromozeka.domain.model.ai.AiExecutionTarget.Worker("exact-worker")) else it
        })
        val envelope = GromozekaServerEnvelope(id = "catalog-file", payload = AiCatalogResponse(RemoteAiCatalogSnapshot.from(AiCatalogSnapshot(catalog, 9))))
        for (decoded in listOf(RemoteProtocolCodec.decodeServerText(RemoteProtocolCodec.encodeServerText(envelope)),
            RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(envelope)))) {
            val snapshot = (decoded.payload as AiCatalogResponse).snapshot
            val connection = snapshot.catalog.connections.filterIsInstance<AiConnection.OpenAiApi>().single()
            kotlin.test.assertEquals(reference, connection.apiKey)
            kotlin.test.assertEquals(settings, connection.httpSettings)
            val state = snapshot.secretStates.single { it.slot == AiCatalogSecretSlot.ConnectionApiKey(connection.id) }
            kotlin.test.assertEquals(AiCatalogSecretState.Source.FILE, state.source)
            kotlin.test.assertEquals(reference.path, state.filePath)
            assertNull(state.environmentVariableName)
        }
    }

    @Test
    fun `remote AI catalog preserves secret state without serializing its value`() {
        val secretValue = "inline-secret-never-return"
        val seedCatalog = RuntimeCatalogTemplateApplicationService().createSeed().aiCatalog
        val catalog = seedCatalog.copy(
            connections = seedCatalog.connections.map { connection ->
                if (connection is AiConnection.OpenAiApi) {
                    connection.copy(apiKey = SecretRef.Inline(secretValue), baseUrl = "https://gateway.example/api/llm",
                        httpSettings = com.gromozeka.domain.model.ai.AiHttpSettings(authentication = com.gromozeka.domain.model.ai.AiHttpSettings.Authentication.BEARER,
                            extraHeaders = mapOf("User-Agent" to "Catalog-fixture"), maxAttempts = 4))
                } else {
                    connection
                }
            }
        )
        val fullSnapshot = AiCatalogSnapshot(catalog, revision = 7)

        assertFailsWith<IllegalArgumentException> {
            RemoteAiCatalogSnapshot(
                catalog = catalog,
                revision = fullSnapshot.revision,
                runtimeEnabledConnectionIds = emptySet(),
                secretStates = catalog.secretStates(),
            )
        }

        val encoded = RemoteProtocolCodec.encodeServerText(
            GromozekaServerEnvelope(
                id = "catalog-response",
                payload = AiCatalogResponse(RemoteAiCatalogSnapshot.from(fullSnapshot)),
            )
        )
        val response = RemoteProtocolCodec.decodeServerText(encoded).payload as AiCatalogResponse
        val connection = response.snapshot.catalog.connections
            .filterIsInstance<AiConnection.OpenAiApi>()
            .single()

        assertFalse(encoded.contains(secretValue))
        assertNull(connection.apiKey)
        assertTrue(
            response.snapshot.secretStates.any {
                it.slot == AiCatalogSecretSlot.ConnectionApiKey(connection.id) &&
                    it.source == AiCatalogSecretState.Source.INLINE
            }
        )
    }
}
