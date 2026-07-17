package com.gromozeka.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceContextInstructionTest {
    @Test
    fun `renders typed user-selected paths as escaped runtime context`() {
        val instruction = Conversation.Message.Instruction.WorkspaceContext(
            references = listOf(
                WorkspaceContextReference(
                    path = "/tmp/a&b/File.kt",
                    name = "File.kt",
                    kind = WorkspacePath.Kind.FILE,
                ),
                WorkspaceContextReference(
                    path = "/tmp/<docs>",
                    name = "<docs>",
                    kind = WorkspacePath.Kind.DIRECTORY,
                ),
            )
        )

        assertEquals(
            """
            <workspace_context purpose="Paths explicitly selected by the user for this message; inspect them when relevant">
              <reference kind="file" path="/tmp/a&amp;b/File.kt">File.kt</reference>
              <reference kind="directory" path="/tmp/&lt;docs&gt;">&lt;docs&gt;</reference>
            </workspace_context>
            """.trimIndent(),
            instruction.toXmlLine(),
        )
    }
}
