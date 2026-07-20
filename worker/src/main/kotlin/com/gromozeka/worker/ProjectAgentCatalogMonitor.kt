package com.gromozeka.worker

import com.gromozeka.domain.model.AgentCatalogImportProposal
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.service.AgentCatalogDiscoveryService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path

@Component
@DependsOn("conversationRuntimeWorkerDescriptor")
class ProjectAgentCatalogMonitor(
    private val properties: ConversationRuntimeWorkerProperties,
    private val discoveryService: AgentCatalogDiscoveryService,
) {
    private val log = KLoggers.logger(this)
    private val scanner = ProjectAgentCatalogScanner()
    private val stateStore = ProjectAgentCatalogStateStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    @PostConstruct
    fun start() {
        monitorJob = scope.launch {
            while (isActive) {
                properties.workspaces.forEach { workspace ->
                    runCatching { synchronize(workspace) }
                        .onFailure { error ->
                            log.error(error) {
                                "Agent catalog scan failed for workspace ${workspace.id}: ${error.message}"
                            }
                        }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @PreDestroy
    fun stop() {
        monitorJob?.cancel()
        scope.cancel()
    }

    private suspend fun synchronize(
        workspace: ConversationRuntimeWorkerProperties.FilesystemWorkspace,
    ) {
        val workerId = properties.id.trim()
        val workspaceId = Workspace.Id(workspace.id)
        val root = Path.of(workspace.rootPath).toAbsolutePath().normalize()
        val snapshot = scanner.scan(workspace, workerId)
        if (snapshot == null) {
            discoveryService.withdraw(workspaceId, workerId)
            return
        }

        stateStore.ensureLocalDirectoryIgnored(root)
        val stateKey = "${workspace.projectId}:${workspace.id}"
        val state = stateStore.load(root)
        val proposal = discoveryService.find(workspaceId, workerId)
        if (
            proposal != null &&
            proposal.catalogHash == snapshot.catalogHash &&
            proposal.status != AgentCatalogImportProposal.Status.PENDING
        ) {
            stateStore.save(
                root,
                state.copy(
                    acknowledgedCatalogHashes = state.acknowledgedCatalogHashes +
                        (stateKey to snapshot.catalogHash)
                )
            )
            discoveryService.acknowledge(
                workspaceId = workspaceId,
                workerId = workerId,
                catalogHash = snapshot.catalogHash,
                status = proposal.status,
            )
            log.info {
                "Acknowledged ${proposal.status.name.lowercase()} agent catalog " +
                    "${snapshot.catalogHash.take(12)} for workspace ${workspace.id}"
            }
            return
        }

        if (state.acknowledgedCatalogHashes[stateKey] == snapshot.catalogHash) {
            if (proposal?.status == AgentCatalogImportProposal.Status.PENDING) {
                discoveryService.withdraw(workspaceId, workerId)
            }
            return
        }

        discoveryService.report(snapshot)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
    }
}

private class ProjectAgentCatalogStateStore {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun ensureLocalDirectoryIgnored(workspaceRoot: Path) {
        val gromozekaDirectory = workspaceRoot.resolve(GROMOZEKA_DIRECTORY)
        Files.createDirectories(gromozekaDirectory)
        val gitignore = gromozekaDirectory.resolve(GITIGNORE_FILE)
        val existing = if (Files.exists(gitignore)) Files.readString(gitignore) else ""
        val lines = existing.lineSequence().map(String::trim).toSet()
        if (LOCAL_IGNORE_ENTRY !in lines) {
            val prefix = if (existing.isEmpty() || existing.endsWith("\n")) existing else "$existing\n"
            Files.writeString(gitignore, "$prefix$LOCAL_IGNORE_ENTRY\n", StandardCharsets.UTF_8)
        }
    }

    fun load(workspaceRoot: Path): AgentCatalogImportState {
        val path = statePath(workspaceRoot)
        return if (Files.exists(path)) {
            json.decodeFromString(Files.readString(path, StandardCharsets.UTF_8))
        } else {
            AgentCatalogImportState()
        }
    }

    fun save(
        workspaceRoot: Path,
        state: AgentCatalogImportState,
    ) {
        val target = statePath(workspaceRoot)
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(state), StandardCharsets.UTF_8)
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun statePath(workspaceRoot: Path): Path =
        workspaceRoot.resolve(GROMOZEKA_DIRECTORY).resolve(LOCAL_DIRECTORY).resolve(STATE_FILE)

    private companion object {
        const val GROMOZEKA_DIRECTORY = ".gromozeka"
        const val LOCAL_DIRECTORY = ".local"
        const val STATE_FILE = "import-state.json"
        const val GITIGNORE_FILE = ".gitignore"
        const val LOCAL_IGNORE_ENTRY = ".local/"
    }
}

@Serializable
private data class AgentCatalogImportState(
    val acknowledgedCatalogHashes: Map<String, String> = emptyMap(),
)
