package com.gromozeka.worker

import com.gromozeka.domain.model.AgentCatalogSnapshot
import com.gromozeka.domain.model.AgentCatalogSourceAgent
import com.gromozeka.domain.model.AgentCatalogSourcePrompt
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

class ProjectAgentCatalogScanner {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun scan(
        workspace: ConversationRuntimeWorkerProperties.FilesystemWorkspace,
        workerId: String,
    ): AgentCatalogSnapshot? {
        val root = Path.of(workspace.rootPath).toAbsolutePath().normalize()
        require(Files.isDirectory(root)) { "Workspace root does not exist: $root" }
        val catalogRoot = root.resolve(GROMOZEKA_DIRECTORY)
        val promptFiles = catalogFiles(catalogRoot.resolve(PROMPTS_DIRECTORY), ".md")
        val agentFiles = catalogFiles(catalogRoot.resolve(AGENTS_DIRECTORY), ".json")
        if (promptFiles.isEmpty() && agentFiles.isEmpty()) {
            return null
        }

        val files = (promptFiles.map { CatalogFile("prompts/${it.fileName}", it) } +
            agentFiles.map { CatalogFile("agents/${it.fileName}", it) })
            .sortedBy { it.relativePath }
        val technicalError = validateTechnicalLimits(promptFiles, agentFiles)
        val contents = if (technicalError == null) {
            files.associateWith { Files.readAllBytes(it.path) }
        } else {
            emptyMap()
        }
        val catalogHash = if (technicalError == null) {
            contentHash(files, contents)
        } else {
            metadataHash(files)
        }
        if (technicalError != null) {
            return snapshot(workspace, workerId, catalogHash, scannerError = technicalError)
        }

        return runCatching {
            val prompts = promptFiles.map { path ->
                val bytes = checkNotNull(contents[CatalogFile("prompts/${path.fileName}", path)])
                AgentCatalogSourcePrompt(
                    sourcePath = path.fileName.toString(),
                    name = path.fileName.toString().removeSuffix(".md"),
                    content = decodeUtf8(path, bytes),
                )
            }
            val agents = agentFiles.map { path ->
                val bytes = checkNotNull(contents[CatalogFile("agents/${path.fileName}", path)])
                val source = json.decodeFromString<AgentSourceFile>(decodeUtf8(path, bytes))
                AgentCatalogSourceAgent(
                    sourcePath = path.fileName.toString(),
                    name = source.name,
                    prompts = source.prompts,
                    runtimeSelection = source.runtimeSelection,
                    runtimeOverrides = source.runtimeOverrides,
                    tools = source.tools,
                    description = source.description,
                )
            }
            snapshot(workspace, workerId, catalogHash, prompts, agents)
        }.getOrElse { error ->
            snapshot(
                workspace = workspace,
                workerId = workerId,
                catalogHash = catalogHash,
                scannerError = error.message ?: error::class.simpleName ?: "Agent catalog parsing failed",
            )
        }
    }

    private fun catalogFiles(directory: Path, suffix: String): List<Path> {
        if (!Files.exists(directory)) {
            return emptyList()
        }
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "Agent catalog path is not a directory: $directory"
        }
        return Files.list(directory).use { paths ->
            paths.filter { path ->
                path.fileName.toString().endsWith(suffix)
            }.map { path ->
                require(!Files.isSymbolicLink(path)) { "Agent catalog symlinks are not allowed: $path" }
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    "Agent catalog entry is not a regular file: $path"
                }
                path
            }.sorted().toList()
        }
    }

    private fun validateTechnicalLimits(
        promptFiles: List<Path>,
        agentFiles: List<Path>,
    ): String? {
        if (promptFiles.size > MAX_PROMPTS) {
            return "Agent catalog contains more than $MAX_PROMPTS prompt files"
        }
        if (agentFiles.size > MAX_AGENTS) {
            return "Agent catalog contains more than $MAX_AGENTS agent files"
        }
        promptFiles.firstOrNull { Files.size(it) > MAX_PROMPT_BYTES }?.let {
            return "Project prompt exceeds $MAX_PROMPT_BYTES bytes: ${it.fileName}"
        }
        agentFiles.firstOrNull { Files.size(it) > MAX_AGENT_BYTES }?.let {
            return "Project agent exceeds $MAX_AGENT_BYTES bytes: ${it.fileName}"
        }
        val totalBytes = (promptFiles + agentFiles).sumOf(Files::size)
        return if (totalBytes > MAX_CATALOG_BYTES) {
            "Agent catalog exceeds $MAX_CATALOG_BYTES bytes"
        } else {
            null
        }
    }

    private fun contentHash(
        files: List<CatalogFile>,
        contents: Map<CatalogFile, ByteArray>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            val bytes = checkNotNull(contents[file])
            digest.update(file.relativePath.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        return digest.digest().toHex()
    }

    private fun metadataHash(files: List<CatalogFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            digest.update(file.relativePath.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(Files.size(file.path)).array())
            digest.update(
                ByteBuffer.allocate(Long.SIZE_BYTES)
                    .putLong(Files.getLastModifiedTime(file.path, LinkOption.NOFOLLOW_LINKS).toMillis())
                    .array()
            )
        }
        return digest.digest().toHex()
    }

    private fun decodeUtf8(path: Path, bytes: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun snapshot(
        workspace: ConversationRuntimeWorkerProperties.FilesystemWorkspace,
        workerId: String,
        catalogHash: String,
        prompts: List<AgentCatalogSourcePrompt> = emptyList(),
        agents: List<AgentCatalogSourceAgent> = emptyList(),
        scannerError: String? = null,
    ): AgentCatalogSnapshot =
        AgentCatalogSnapshot(
            projectId = Project.Id(workspace.projectId),
            workspaceId = Workspace.Id(workspace.id),
            workspaceName = workspace.name,
            workerId = workerId,
            catalogHash = catalogHash,
            prompts = prompts,
            agents = agents,
            scannerError = scannerError,
            detectedAt = Clock.System.now(),
        )

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    @Serializable
    private data class AgentSourceFile(
        val name: String,
        val prompts: List<String>,
        val runtimeSelection: AiRuntimeSelection,
        val runtimeOverrides: AiRuntimeOverrides = AiRuntimeOverrides(),
        val tools: List<String> = emptyList(),
        val description: String? = null,
    )

    private data class CatalogFile(
        val relativePath: String,
        val path: Path,
    )

    private companion object {
        const val GROMOZEKA_DIRECTORY = ".gromozeka"
        const val PROMPTS_DIRECTORY = "prompts"
        const val AGENTS_DIRECTORY = "agents"
        const val MAX_PROMPTS = 256
        const val MAX_AGENTS = 128
        const val MAX_PROMPT_BYTES = 1_000_000L
        const val MAX_AGENT_BYTES = 256_000L
        const val MAX_CATALOG_BYTES = 8_000_000L
    }
}
