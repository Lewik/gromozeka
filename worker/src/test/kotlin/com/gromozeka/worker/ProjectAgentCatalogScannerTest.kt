package com.gromozeka.worker

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectAgentCatalogScannerTest {
    private val scanner = ProjectAgentCatalogScanner()

    @Test
    fun `scans strict source catalog and changes hash with content`() {
        val root = createTempDirectory("agent-catalog")
        try {
            writePrompt(root, "role.md", "First version")
            writeAgent(root, "architect.agent.json")
            val workspace = workspace(root.toString())

            val first = assertNotNull(scanner.scan(workspace, "worker-1"))
            assertNull(first.scannerError)
            assertEquals(listOf("role.md"), first.prompts.map { it.sourcePath })
            assertEquals(listOf("architect.agent.json"), first.agents.map { it.sourcePath })
            assertEquals(listOf("project:role.md", "env"), first.agents.single().prompts)

            writePrompt(root, "role.md", "Second version")
            val second = assertNotNull(scanner.scan(workspace, "worker-1"))

            assertNotEquals(first.catalogHash, second.catalogHash)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects old domain-shaped agent files`() {
        val root = createTempDirectory("agent-catalog")
        try {
            writePrompt(root, "role.md", "Role")
            val agentsDirectory = Files.createDirectories(root.resolve(".gromozeka/agents"))
            Files.writeString(
                agentsDirectory.resolve("legacy.agent.json"),
                """
                {
                  "id": "workspace:legacy.agent.json",
                  "name": "Legacy",
                  "prompts": ["project:role.md"],
                  "runtimeSelection": {"modelConfigurationId": "test-model"}
                }
                """.trimIndent()
            )

            val snapshot = assertNotNull(scanner.scan(workspace(root.toString()), "worker-1"))

            assertNotNull(snapshot.scannerError)
            assertEquals(emptyList(), snapshot.agents)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ignores workspaces without an agent catalog`() {
        val root = createTempDirectory("agent-catalog")
        try {
            assertNull(scanner.scan(workspace(root.toString()), "worker-1"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writePrompt(
        root: java.nio.file.Path,
        name: String,
        content: String,
    ) {
        val directory = Files.createDirectories(root.resolve(".gromozeka/prompts"))
        Files.writeString(directory.resolve(name), content)
    }

    private fun writeAgent(
        root: java.nio.file.Path,
        name: String,
    ) {
        val directory = Files.createDirectories(root.resolve(".gromozeka/agents"))
        Files.writeString(
            directory.resolve(name),
            """
            {
              "name": "Architect",
              "prompts": ["project:role.md", "env"],
              "runtimeSelection": {"modelConfigurationId": "test-model"},
              "description": "Architecture"
            }
            """.trimIndent()
        )
    }

    private fun workspace(rootPath: String) =
        ConversationRuntimeWorkerProperties.FilesystemWorkspace(
            id = "workspace-1",
            projectId = "project-1",
            projectName = "Project",
            name = "Checkout",
            rootPath = rootPath,
        )
}
