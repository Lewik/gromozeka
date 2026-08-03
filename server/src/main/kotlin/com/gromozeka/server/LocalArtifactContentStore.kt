package com.gromozeka.server

import com.gromozeka.application.service.SettingsService
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.service.ArtifactContentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Service
class LocalArtifactContentStore(
    settingsService: SettingsService,
) : ArtifactContentStore {
    private val root = settingsService.gromozekaHome.toPath().resolve("artifacts")

    override suspend fun write(id: Artifact.Id, content: ByteArray) = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        val target = path(id)
        val temporary = Files.createTempFile(root, ".artifact-", ".tmp")
        try {
            Files.write(temporary, content)
            runCatching {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        Unit
    }

    override suspend fun read(id: Artifact.Id): ByteArray = withContext(Dispatchers.IO) {
        Files.readAllBytes(path(id))
    }

    override suspend fun delete(id: Artifact.Id) = withContext(Dispatchers.IO) {
        Files.deleteIfExists(path(id))
        Unit
    }

    override suspend fun listIds(): Set<Artifact.Id> = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(root)) return@withContext emptySet()
        Files.list(root).use { paths ->
            paths.iterator().asSequence().mapNotNull { path ->
                ARTIFACT_FILE_PATTERN.matchEntire(path.fileName.toString())
                    ?.groupValues
                    ?.get(1)
                    ?.let(Artifact::Id)
            }.toSet()
        }
    }

    private fun path(id: Artifact.Id) = root.resolve("${id.safeValue()}.bin")

    private fun Artifact.Id.safeValue(): String {
        require(value.matches(SAFE_ID_PATTERN)) { "Invalid artifact id" }
        return value
    }

    companion object {
        private val SAFE_ID_PATTERN = Regex("[0-9a-fA-F-]{36}")
        private val ARTIFACT_FILE_PATTERN = Regex("([0-9a-fA-F-]{36})\\.bin")
    }
}
