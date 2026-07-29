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
    fun `remote AI catalog preserves secret state without serializing its value`() {
        val secretValue = "inline-secret-never-return"
        val seedCatalog = RuntimeCatalogTemplateApplicationService().createSeed().aiCatalog
        val catalog = seedCatalog.copy(
            connections = seedCatalog.connections.map { connection ->
                if (connection is AiConnection.OpenAiApi) {
                    connection.copy(apiKey = SecretRef.Inline(secretValue))
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
