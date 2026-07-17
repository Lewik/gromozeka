package com.gromozeka.application.service

import com.gromozeka.domain.model.WorkspacePath
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkspaceFileSystemApplicationServiceTest {
    private val service = WorkspaceFileSystemApplicationService()

    @Test
    fun `browses canonical server paths with directories first`() = runBlocking {
        val root = Files.createTempDirectory("gromozeka-workspace-test")
        try {
            root.resolve("zeta").createDirectory()
            root.resolve("Alpha").createDirectory()
            root.resolve("notes.md").createFile()

            val listing = service.browse(root.toString())

            assertEquals(root.toRealPath().toString(), listing.directory.path)
            assertEquals(WorkspacePath.Kind.DIRECTORY, listing.directory.kind)
            assertEquals(root.parent.toRealPath().toString(), listing.parentPath)
            assertEquals(
                listOf(
                    "Alpha" to WorkspacePath.Kind.DIRECTORY,
                    "zeta" to WorkspacePath.Kind.DIRECTORY,
                    "notes.md" to WorkspacePath.Kind.FILE,
                ),
                listing.entries.map { it.name to it.kind },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects a file as browse root`() = runBlocking {
        val file = Files.createTempFile("gromozeka-workspace-test", ".txt")
        try {
            assertFailsWith<IllegalArgumentException> {
                service.browse(file.toString())
            }
            Unit
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
