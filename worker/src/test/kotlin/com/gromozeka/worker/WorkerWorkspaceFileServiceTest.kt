package com.gromozeka.worker

import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.WorkspacePathReference
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerWorkspaceFileReadRequest
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkerWorkspaceFileServiceTest {
    private val service = WorkerWorkspaceFileService()

    @Test
    fun `reads exact UTF-8 content relative to workspace root`() = runBlocking {
        val root = Files.createTempDirectory("worker-workspace")
        val file = root.resolve("docs/memory.md")
        file.parent.createDirectories()
        val content = "  Первая строка  \n\nSecond line.  \n"
        file.writeText(content)

        val result = service.read(request(root.toString(), "docs/memory.md", 1024))

        assertEquals(content, result.content.utf8TextOrNull())
        assertEquals(file.toFile().canonicalPath, result.resolvedPath)
        assertEquals("memory.md", result.fileName)
        assertEquals(content.encodeToByteArray().size.toLong(), result.sizeBytes)
    }

    @Test
    fun `rejects content larger than requested limit`() = runBlocking {
        val root = Files.createTempDirectory("worker-workspace")
        root.resolve("memory.md").writeText("123456")

        val error = assertFailsWith<IllegalArgumentException> {
            service.read(request(root.toString(), "memory.md", 5))
        }

        assertTrue(error.message.orEmpty().contains("too large"))
    }

    @Test fun `binary file bytes survive without UTF-8 decoding`() = runBlocking {
        val root = Files.createTempDirectory("worker-binary-file")
        try {
            val bytes = byteArrayOf(0, -1, -2, 32, 10)
            Files.write(root.resolve("image.png"), bytes)
            val result = service.read(request(root.toString(), "image.png", bytes.size.toLong()))
            kotlin.test.assertContentEquals(bytes, result.content.bytes())
            kotlin.test.assertNull(result.content.utf8TextOrNull())
        } finally { root.toFile().deleteRecursively() }
    }

    private fun request(
        rootPath: String,
        path: String,
        maxBytes: Long,
    ) = WorkerWorkspaceFileReadRequest(
        target = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId("worker-1"),
            sessionId = ConversationRuntimeWorkerSessionId("session-1"),
        ),
        reference = WorkspacePathReference(WorkspaceMount.Id("mount-1"), path),
        workspaceRootPath = rootPath,
        maxBytes = maxBytes,
    )
}
