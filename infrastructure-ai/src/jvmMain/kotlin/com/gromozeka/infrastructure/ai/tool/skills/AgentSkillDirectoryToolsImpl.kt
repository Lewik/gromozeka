package com.gromozeka.infrastructure.ai.tool.skills

import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.service.AgentSkillDirectoryImportClient
import com.gromozeka.domain.service.AgentSkillDirectoryImportRequest
import com.gromozeka.domain.service.AgentSkillPackageClient
import com.gromozeka.domain.service.AgentSkillPackageRequest
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredAgentDefinitionId
import com.gromozeka.domain.tool.requiredProjectId
import com.gromozeka.domain.tool.requiredUserId
import com.gromozeka.domain.tool.skills.ExportAgentSkillToDirectoryRequest
import com.gromozeka.domain.tool.skills.ExportAgentSkillToDirectoryTool
import com.gromozeka.domain.tool.skills.ImportAgentSkillFromDirectoryRequest
import com.gromozeka.domain.tool.skills.ImportAgentSkillFromDirectoryTool
import com.gromozeka.shared.uuid.uuid7
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class LocalAgentSkillDirectoryService(
    private val packageClient: AgentSkillPackageClient,
    private val importClient: AgentSkillDirectoryImportClient,
) {
    suspend fun export(
        request: AgentSkillPackageRequest,
        directoryPath: String,
    ): DirectoryExportResult {
        val skillPackage = packageClient.fetch(request)
        require(skillPackage.skill.projectId == request.projectId) {
            "Agent Skill '${skillPackage.skill.name}' belongs to another project"
        }
        require(skillPackage.skill.id == request.skillId) {
            "Server returned Agent Skill '${skillPackage.skill.id.value}' instead of '${request.skillId.value}'"
        }
        require(skillPackage.skill.contentHash == request.contentHash) {
            "Server returned stale Agent Skill package '${skillPackage.skill.contentHash}' instead of '${request.contentHash}'"
        }

        val target = Path.of(directoryPath).toAbsolutePath().normalize()
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            "Agent Skill export destination already exists: $target"
        }
        val parent = requireNotNull(target.parent) {
            "Agent Skill export destination must have a parent directory"
        }
        Files.createDirectories(parent)
        val temporary = parent.resolve(".${target.fileName}.${uuid7()}.tmp")
        try {
            Files.createDirectory(temporary)
            skillPackage.files.forEach { file ->
                val relative = normalizedPackagePath(file.path)
                val destination = temporary.resolve(relative).normalize()
                require(destination.startsWith(temporary)) {
                    "Agent Skill file path escapes the export directory: ${file.path}"
                }
                Files.createDirectories(destination.parent)
                Files.write(
                    destination,
                    file.content,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
            }
            moveDirectory(temporary, target)
        } catch (error: Throwable) {
            if (temporary.exists()) {
                deleteTree(temporary)
            }
            throw error
        }
        return DirectoryExportResult(
            name = skillPackage.skill.name,
            contentHash = skillPackage.skill.contentHash,
            directoryPath = target.toString(),
            fileCount = skillPackage.files.size,
            sizeBytes = skillPackage.files.sumOf { it.content.size.toLong() },
        )
    }

    suspend fun importPackage(
        request: AgentSkillDirectoryImportRequest,
    ) = importClient.importPackage(request)

    fun readPackage(directoryPath: String): AgentSkillPackageSource {
        val root = Path.of(directoryPath).toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(root)) {
            "Agent Skill package directory must not be a symbolic link: $root"
        }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Agent Skill package path is not a directory: $root"
        }
        val directoryName = requireNotNull(root.fileName).toString()
            .takeIf { it.isNotBlank() }
            ?: error("Agent Skill package directory must have a name")
        var totalSize = 0L
        var fileCount = 0
        val files = Files.walk(root).use { paths ->
            paths.sorted().iterator().asSequence().mapNotNull { path ->
                require(!Files.isSymbolicLink(path)) {
                    "Agent Skill packages must not contain symbolic links: $path"
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    return@mapNotNull null
                }
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    "Agent Skill package contains an unsupported filesystem entry: $path"
                }
                fileCount += 1
                require(fileCount <= MAX_FILES) {
                    "Agent Skill package contains more than $MAX_FILES files"
                }
                val size = Files.size(path)
                require(size <= MAX_FILE_BYTES) {
                    "Agent Skill file exceeds $MAX_FILE_BYTES bytes: $path"
                }
                totalSize += size
                require(totalSize <= MAX_PACKAGE_BYTES) {
                    "Agent Skill package exceeds $MAX_PACKAGE_BYTES bytes"
                }
                AgentSkillFile(
                    path = root.relativize(path).joinToString("/") { it.toString() },
                    content = Files.readAllBytes(path),
                )
            }.toList()
        }
        return AgentSkillPackageSource(directoryName, files)
    }

    private fun normalizedPackagePath(rawPath: String): Path {
        require(rawPath.isNotBlank() && rawPath.length <= 1_000 && '\\' !in rawPath && !rawPath.startsWith('/')) {
            "Agent Skill file path must be a normalized relative path: $rawPath"
        }
        require(rawPath.none(Char::isISOControl)) {
            "Agent Skill file path must be a normalized relative path: $rawPath"
        }
        val segments = rawPath.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Agent Skill file path must be a normalized relative path: $rawPath"
        }
        return Path.of(segments.first(), *segments.drop(1).toTypedArray())
    }

    private fun moveDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    data class DirectoryExportResult(
        val name: String,
        val contentHash: String,
        val directoryPath: String,
        val fileCount: Int,
        val sizeBytes: Long,
    )

    private companion object {
        const val MAX_FILES = 2_000
        const val MAX_FILE_BYTES = 16_000_000L
        const val MAX_PACKAGE_BYTES = 64_000_000L
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class ExportAgentSkillToDirectoryToolImpl(
    private val directoryService: LocalAgentSkillDirectoryService,
) : ExportAgentSkillToDirectoryTool {
    override fun execute(
        request: ExportAgentSkillToDirectoryRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any> = runBlocking {
        context?.cancellationSignal?.throwIfCancellationRequested()
        val result = directoryService.export(
            request = AgentSkillPackageRequest(
                projectId = context.requiredProjectId(),
                agentDefinitionId = context.requiredAgentDefinitionId(),
                skillId = com.gromozeka.domain.model.AgentSkill.Id(request.skill_id),
                contentHash = request.content_hash,
            ),
            directoryPath = request.directory_path,
        )
        context?.cancellationSignal?.throwIfCancellationRequested()
        mapOf(
            "success" to true,
            "name" to result.name,
            "content_hash" to result.contentHash,
            "directory_path" to result.directoryPath,
            "file_count" to result.fileCount,
            "size_bytes" to result.sizeBytes,
        )
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class ImportAgentSkillFromDirectoryToolImpl(
    private val directoryService: LocalAgentSkillDirectoryService,
) : ImportAgentSkillFromDirectoryTool {
    override fun execute(
        request: ImportAgentSkillFromDirectoryRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any> = runBlocking {
        context?.cancellationSignal?.throwIfCancellationRequested()
        val source = directoryService.readPackage(request.directory_path)
        context?.cancellationSignal?.throwIfCancellationRequested()
        val skill = directoryService.importPackage(
            AgentSkillDirectoryImportRequest(
                projectId = context.requiredProjectId(),
                agentDefinitionId = context.requiredAgentDefinitionId(),
                actorUserId = context.requiredUserId(),
                source = source,
                expectedContentHash = request.expected_content_hash,
            )
        )
        context?.cancellationSignal?.throwIfCancellationRequested()
        mapOf(
            "success" to true,
            "skill_id" to skill.id.value,
            "name" to skill.name,
            "content_hash" to skill.contentHash,
            "file_count" to source.files.size,
            "size_bytes" to source.files.sumOf { it.content.size.toLong() },
        )
    }
}
