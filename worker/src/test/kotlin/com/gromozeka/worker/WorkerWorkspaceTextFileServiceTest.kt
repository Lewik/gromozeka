package com.gromozeka.worker

import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.WorkspacePathReference
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerWorkspaceTextFileReadRequest
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkerWorkspaceTextFileServiceTest {
    private val service = WorkerWorkspaceTextFileService()

    @Test
    fun `reads exact UTF-8 content relative to workspace root`() = runBlocking {
        val root = Files.createTempDirectory("worker-workspace")
        val file = root.resolve("docs/memory.md")
        file.parent.createDirectories()
        val content = "  Первая строка  \n\nSecond line.  \n"
        file.writeText(content)

        val result = service.read(request(root.toString(), "docs/memory.md", 1024))

        assertEquals(content, result.content)
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

    private fun request(
        rootPath: String,
        path: String,
        maxBytes: Long,
    ) = WorkerWorkspaceTextFileReadRequest(
        target = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId("worker-1"),
            sessionId = ConversationRuntimeWorkerSessionId("session-1"),
        ),
        reference = WorkspacePathReference(WorkspaceMount.Id("mount-1"), path),
        workspaceRootPath = rootPath,
        maxBytes = maxBytes,
    )
}
