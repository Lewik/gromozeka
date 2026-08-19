package com.gromozeka.infrastructure.ai.tool.skills

import com.gromozeka.domain.service.AgentSkillMaterializationResult
import com.gromozeka.domain.service.AgentSkillMaterializationService
import com.gromozeka.domain.service.AgentSkillPackageClient
import com.gromozeka.domain.service.AgentSkillPackageRequest
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredAgentDefinitionId
import com.gromozeka.domain.tool.requiredProjectId
import com.gromozeka.domain.tool.requiredWorkspaceMountId
import com.gromozeka.domain.tool.requiredWorkspaceRootPath
import com.gromozeka.domain.tool.skills.MaterializeAgentSkillRequest
import com.gromozeka.domain.tool.skills.MaterializeAgentSkillTool
import com.gromozeka.shared.uuid.uuid7
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class LocalAgentSkillMaterializationService(
    private val packageClient: AgentSkillPackageClient,
) : AgentSkillMaterializationService {
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun materialize(
        request: AgentSkillPackageRequest,
        workspaceRootPath: String,
    ): AgentSkillMaterializationResult {
        val workspaceRoot = Path.of(workspaceRootPath).toRealPath()
        require(Files.isDirectory(workspaceRoot)) {
            "Workspace root path is not a directory: $workspaceRoot"
        }
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
        val target = workspaceRoot
            .resolve(SKILL_ROOT_DIRECTORY)
            .resolve(skillPackage.skill.name)
            .resolve(skillPackage.skill.contentHash)
            .normalize()
        require(target.startsWith(workspaceRoot)) {
            "Agent Skill materialization target escapes the workspace"
        }
        val lock = locks.computeIfAbsent(target.toString()) { Mutex() }
        return lock.withLock {
            val materializationParent = ensureMaterializationParent(workspaceRoot, skillPackage.skill.name)
            check(materializationParent == target.parent) {
                "Agent Skill materialization parent changed during resolution"
            }
            require(!Files.isSymbolicLink(target)) {
                "Agent Skill materialization target must not be a symbolic link: $target"
            }
            val alreadyPresent = isComplete(target, skillPackage.skill.contentHash)
            if (!alreadyPresent) {
                materializePackage(target, skillPackage.files, skillPackage.skill.contentHash)
            }
            AgentSkillMaterializationResult(
                skill = skillPackage.skill,
                directoryPath = target.toString(),
                fileCount = skillPackage.files.size,
                sizeBytes = skillPackage.files.sumOf { it.content.size.toLong() },
                alreadyPresent = alreadyPresent,
            )
        }
    }

    private fun materializePackage(
        target: Path,
        files: List<com.gromozeka.domain.model.AgentSkillFile>,
        contentHash: String,
    ) {
        Files.createDirectories(target.parent)
        if (target.exists()) {
            deleteTree(target)
        }
        Files.deleteIfExists(completionMarker(target))
        val temporary = target.parent.resolve(".${target.fileName}.${uuid7()}.tmp")
        try {
            Files.createDirectory(temporary)
            files.forEach { file ->
                val relative = Path.of(file.path).normalize()
                require(!relative.isAbsolute && relative.none { it.toString() == ".." }) {
                    "Agent Skill file path is not a normalized relative path: ${file.path}"
                }
                val destination = temporary.resolve(relative).normalize()
                require(destination.startsWith(temporary)) {
                    "Agent Skill file path escapes the materialization directory: ${file.path}"
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
            Files.writeString(
                completionMarker(target),
                contentHash,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        } catch (error: Throwable) {
            if (temporary.exists()) {
                deleteTree(temporary)
            }
            throw error
        }
    }

    private fun isComplete(target: Path, contentHash: String): Boolean {
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        val marker = completionMarker(target)
        return marker.exists() &&
            !Files.isSymbolicLink(marker) &&
            runCatching { marker.readText() }.getOrNull() == contentHash
    }

    private fun ensureMaterializationParent(workspaceRoot: Path, skillName: String): Path {
        var current = workspaceRoot
        listOf(".gromozeka", "skills", skillName).forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current) && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    "Agent Skill materialization path must be a real directory: $current"
                }
            } else {
                Files.createDirectory(current)
            }
        }
        return current
    }

    private fun completionMarker(target: Path): Path =
        target.parent.resolve(".${target.fileName}.complete")

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

    private companion object {
        const val SKILL_ROOT_DIRECTORY = ".gromozeka/skills"
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class MaterializeAgentSkillToolImpl(
    private val materializationService: AgentSkillMaterializationService,
) : MaterializeAgentSkillTool {
    override fun execute(
        request: MaterializeAgentSkillRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any> = runBlocking {
        context?.cancellationSignal?.throwIfCancellationRequested()
        val result = materializationService.materialize(
            request = AgentSkillPackageRequest(
                projectId = context.requiredProjectId(),
                agentDefinitionId = context.requiredAgentDefinitionId(),
                workspaceMountId = context.requiredWorkspaceMountId(),
                skillId = com.gromozeka.domain.model.AgentSkill.Id(request.skill_id),
                contentHash = request.content_hash,
            ),
            workspaceRootPath = context.requiredWorkspaceRootPath(),
        )
        context?.cancellationSignal?.throwIfCancellationRequested()
        mapOf(
            "success" to true,
            "name" to result.skill.name,
            "content_hash" to result.skill.contentHash,
            "directory_path" to result.directoryPath,
            "file_count" to result.fileCount,
            "size_bytes" to result.sizeBytes,
            "already_present" to result.alreadyPresent,
        )
    }
}
