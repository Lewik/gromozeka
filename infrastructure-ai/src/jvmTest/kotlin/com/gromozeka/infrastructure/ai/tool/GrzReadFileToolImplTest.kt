package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.service.FileSearchService
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKSPACE_ROOT_PATH
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.ReadFileRequest
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GrzReadFileToolImplTest {
    private val workspace = Files.createTempDirectory("grz-read-file-test")
    private val tool = GrzReadFileToolImpl(
        object : FileSearchService {
            override fun findSimilarFiles(
                targetPath: String,
                workspaceRootPath: String,
                limit: Int,
            ): List<String> = listOf("similar.txt")
        }
    )
    private val context = ToolExecutionContext(
        mapOf(TOOL_CONTEXT_WORKSPACE_ROOT_PATH to workspace.toString())
    )

    @AfterTest
    fun cleanUp() {
        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `reads paginated text as a text tool result`() {
        workspace.resolve("notes.txt").writeText("first\nsecond\nthird\n")

        val result = tool.execute(ReadFileRequest("notes.txt", limit = 1, offset = 1), context)

        assertEquals(
            "2\tsecond\n[Read lines 2-2 of 3 total] (more lines available)",
            assertIs<AiToolResult.Text>(result.single()).content,
        )
    }

    @Test
    fun `returns images as native binary tool results`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        workspace.resolve("screen.png").writeBytes(bytes)

        val result = assertIs<AiToolResult.Binary>(
            tool.execute(ReadFileRequest("screen.png"), context).single()
        )

        assertEquals("screen.png", result.fileName)
        assertEquals("image/png", result.mediaType)
        assertContentEquals(bytes, result.content)
    }

    @Test
    fun `returns PDFs as native binary tool results`() {
        val bytes = "%PDF-1.7".encodeToByteArray()
        workspace.resolve("document.pdf").writeBytes(bytes)

        val result = assertIs<AiToolResult.Binary>(
            tool.execute(ReadFileRequest("document.pdf"), context).single()
        )

        assertEquals("document.pdf", result.fileName)
        assertEquals("application/pdf", result.mediaType)
        assertContentEquals(bytes, result.content)
    }

    @Test
    fun `rejects oversized binary files before reading bytes`() {
        val path = workspace.resolve("oversized.png")
        RandomAccessFile(path.toFile(), "rw").use {
            it.setLength(ArtifactLimits.MAX_FILE_BYTES.toLong() + 1)
        }

        val result = assertIs<AiToolResult.Text>(
            tool.execute(ReadFileRequest("oversized.png"), context).single()
        )

        assertTrue(result.content.contains("exceeds the 25 MB artifact limit"))
    }

    @Test
    fun `suggests nearby files when the requested path is missing`() {
        val result = assertIs<AiToolResult.Text>(
            tool.execute(ReadFileRequest("missing.txt"), context).single()
        )

        assertEquals("File not found: missing.txt\nSuggestions:\n- similar.txt", result.content)
    }
}
