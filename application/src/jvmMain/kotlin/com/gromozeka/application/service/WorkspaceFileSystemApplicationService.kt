package com.gromozeka.application.service

import com.gromozeka.domain.model.WorkspaceDirectoryListing
import com.gromozeka.domain.model.WorkspacePath
import com.gromozeka.domain.service.WorkspaceFileSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class WorkspaceFileSystemApplicationService : WorkspaceFileSystemService {
    override suspend fun browse(
        path: String?,
        includeFiles: Boolean,
    ): WorkspaceDirectoryListing =
        withContext(Dispatchers.IO) {
            val directory = resolveDirectory(path)
            val entries = Files.newDirectoryStream(directory).use { stream ->
                stream.mapNotNull(::toWorkspacePath)
                    .filter { includeFiles || it.kind == WorkspacePath.Kind.DIRECTORY }
            }.sortedWith(
                compareBy<WorkspacePath>(
                    { it.kind.ordinal },
                    { it.name.lowercase() },
                    { it.name },
                )
            )

            WorkspaceDirectoryListing(
                directory = directory.toWorkspacePath(WorkspacePath.Kind.DIRECTORY),
                parentPath = directory.parent?.toString(),
                entries = entries,
            )
        }

    private fun resolveDirectory(path: String?): Path {
        val requested = path
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"))

        require(requested.isAbsolute) { "Workspace path must be absolute: $requested" }

        val resolved = requested.toRealPath()
        require(Files.isDirectory(resolved)) { "Workspace path is not a directory: $resolved" }
        require(Files.isReadable(resolved)) { "Workspace directory is not readable: $resolved" }
        return resolved
    }

    private fun toWorkspacePath(path: Path): WorkspacePath? =
        when {
            Files.isDirectory(path) -> path.toWorkspacePath(WorkspacePath.Kind.DIRECTORY)
            Files.isRegularFile(path) -> path.toWorkspacePath(WorkspacePath.Kind.FILE)
            else -> null
        }

    private fun Path.toWorkspacePath(kind: WorkspacePath.Kind): WorkspacePath =
        WorkspacePath(
            path = toAbsolutePath().normalize().toString(),
            name = fileName?.toString() ?: toString(),
            kind = kind,
        )
}
