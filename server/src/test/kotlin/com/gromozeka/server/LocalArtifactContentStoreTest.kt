package com.gromozeka.server

import com.gromozeka.application.service.SettingsService
import com.gromozeka.domain.model.Artifact
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.UUID
import kotlin.test.*

class LocalArtifactContentStoreTest {
    @Test fun `copies are independent exact files and never overwrite an existing identity`() = runBlocking<Unit> {
        val home = Files.createTempDirectory("artifact-copy-test-").toFile()
        val settings = Mockito.mock(SettingsService::class.java)
        Mockito.`when`(settings.gromozekaHome).thenReturn(home)
        val store = LocalArtifactContentStore(settings)
        try {
            for (bytes in listOf(byteArrayOf(), ByteArray(65539) { it.toByte() })) {
                val source = Artifact.Id(UUID.randomUUID().toString())
                val target = Artifact.Id(UUID.randomUUID().toString())
                store.write(source, bytes)
                store.copy(source, target)
                assertContentEquals(bytes, store.read(source))
                assertContentEquals(bytes, store.read(target))
                store.write(source, "Changed source".encodeToByteArray())
                assertContentEquals(bytes, store.read(target))
                assertFailsWith<FileAlreadyExistsException> { store.copy(source, target) }
                assertContentEquals(bytes, store.read(target))
                store.delete(source)
                assertContentEquals(bytes, store.read(target))
                assertFailsWith<NoSuchFileException> { store.copy(source, Artifact.Id(UUID.randomUUID().toString())) }
                assertFailsWith<IllegalArgumentException> { store.copy(target, target) }
                assertFailsWith<IllegalArgumentException> { store.copy(target, Artifact.Id("../escape")) }
            }
            Files.list(home.toPath().resolve("artifacts")).use { paths ->
                assertTrue(paths.allMatch { it.fileName.toString().endsWith(".bin") }, "No partial files remain after copy errors")
            }
        } finally { assertTrue(home.deleteRecursively()) }
    }
}
