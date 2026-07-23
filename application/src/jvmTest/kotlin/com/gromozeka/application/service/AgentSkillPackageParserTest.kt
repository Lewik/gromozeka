package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AgentSkillPackageParserTest {
    private val parser = AgentSkillPackageParser()

    @Test
    fun `parses standard package metadata and preserves resources`() {
        val parsed = parser.parse(
            skillSource(
                name = "release-check",
                skillMarkdown = """
                    ---
                    name: release-check
                    description: Verify a release before publishing.
                    license: Apache-2.0
                    compatibility: Requires Git.
                    metadata:
                      owner: platform
                    allowed-tools: Bash Read
                    ---
                    # Release check

                    Follow the checklist.
                """.trimIndent(),
                extraFiles = listOf(
                    AgentSkillFile("references/checklist.md", "Check tags.".encodeToByteArray()),
                    AgentSkillFile("scripts/verify.sh", "#!/bin/sh\nexit 0".encodeToByteArray()),
                ),
            )
        )

        assertEquals("release-check", parsed.name)
        assertEquals("Verify a release before publishing.", parsed.description)
        assertEquals("Apache-2.0", parsed.license)
        assertEquals("Requires Git.", parsed.compatibility)
        assertEquals(mapOf("owner" to "platform"), parsed.metadata)
        assertEquals("Bash Read", parsed.allowedTools)
        assertEquals(
            listOf("SKILL.md", "references/checklist.md", "scripts/verify.sh"),
            parsed.files.map { it.path },
        )
    }

    @Test
    fun `package hash is stable across file order and changes with content`() {
        val skillMarkdown = """
            ---
            name: deterministic-skill
            description: Test deterministic package hashing.
            ---
            Follow the instructions.
        """.trimIndent()
        val reference = AgentSkillFile("references/details.md", "Details".encodeToByteArray())
        val first = parser.parse(
            skillSource("deterministic-skill", skillMarkdown, listOf(reference))
        )
        val reordered = parser.parse(
            AgentSkillPackageSource(
                directoryName = "deterministic-skill",
                files = listOf(
                    reference,
                    AgentSkillFile("SKILL.md", skillMarkdown.encodeToByteArray()),
                ),
            )
        )
        val changed = parser.parse(
            skillSource(
                "deterministic-skill",
                skillMarkdown,
                listOf(reference.copy(content = "Changed".encodeToByteArray())),
            )
        )

        assertEquals(first.contentHash, reordered.contentHash)
        assertNotEquals(first.contentHash, changed.contentHash)
    }

    @Test
    fun `rejects package name mismatch and unsafe paths`() {
        assertFailsWith<IllegalArgumentException> {
            parser.parse(
                skillSource(
                    name = "directory-name",
                    skillMarkdown = """
                        ---
                        name: another-name
                        description: Invalid package.
                        ---
                        Instructions.
                    """.trimIndent(),
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parser.parse(
                skillSource(
                    name = "safe-name",
                    skillMarkdown = """
                        ---
                        name: safe-name
                        description: Invalid resource path.
                        ---
                        Instructions.
                    """.trimIndent(),
                    extraFiles = listOf(
                        AgentSkillFile("../secret.txt", "secret".encodeToByteArray())
                    ),
                )
            )
        }
    }

    private fun skillSource(
        name: String,
        skillMarkdown: String,
        extraFiles: List<AgentSkillFile> = emptyList(),
    ): AgentSkillPackageSource =
        AgentSkillPackageSource(
            directoryName = name,
            files = listOf(
                AgentSkillFile("SKILL.md", skillMarkdown.encodeToByteArray())
            ) + extraFiles,
        )
}
