package com.gromozeka.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceContextInstructionTest {
    @Test
    fun `renders typed user-selected paths as escaped runtime context`() {
        val instruction = Conversation.Message.Instruction.WorkspaceContext(
            references = listOf(
                WorkspaceContextReference(
                    relativePath = "src/a&b/File.kt",
                    name = "File.kt",
                    kind = WorkspaceContextReference.Kind.FILE,
                ),
                WorkspaceContextReference(
                    relativePath = "docs/<drafts>",
                    name = "<docs>",
                    kind = WorkspaceContextReference.Kind.DIRECTORY,
                ),
            )
        )

        assertEquals(
            """
            <workspace_context purpose="Paths explicitly selected by the user for this message; inspect them when relevant">
              <reference kind="file" path="src/a&amp;b/File.kt">File.kt</reference>
              <reference kind="directory" path="docs/&lt;drafts&gt;">&lt;docs&gt;</reference>
            </workspace_context>
            """.trimIndent(),
            instruction.toXmlLine(),
        )
    }
}
