package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.AgentSkillDirectoryImportRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WorkerAgentSkillGatewayCodecTest {
    @Test
    fun `round trips directory import with binary files`() {
        val expected = AgentSkillDirectoryImportRequest(
            projectId = Project.Id("project-1"),
            agentDefinitionId = AgentDefinition.Id("agent-1"),
            actorUserId = User.Id("user-1"),
            source = AgentSkillPackageSource(
                directoryName = "release-check",
                files = listOf(AgentSkillFile("assets/icon.bin", byteArrayOf(0, 1, 2))),
            ),
            expectedContentHash = "a".repeat(64),
        )

        val actual = WorkerAgentSkillImportGatewayCodec.decodeRequest(
            WorkerAgentSkillImportGatewayCodec.encodeRequest(expected)
        )

        assertEquals(expected.projectId, actual.projectId)
        assertEquals(expected.agentDefinitionId, actual.agentDefinitionId)
        assertEquals(expected.actorUserId, actual.actorUserId)
        assertEquals(expected.source.directoryName, actual.source.directoryName)
        assertEquals(expected.expectedContentHash, actual.expectedContentHash)
        assertEquals("assets/icon.bin", actual.source.files.single().path)
        assertContentEquals(expected.source.files.single().content, actual.source.files.single().content)
    }
}
