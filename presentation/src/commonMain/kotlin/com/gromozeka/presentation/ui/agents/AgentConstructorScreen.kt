package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentTemplate
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.PromptTemplate
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.RuntimeCatalogTemplateService
import com.gromozeka.presentation.ui.CompactButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

private enum class RuntimeCatalogTab(val title: String) {
    Agents("Agents"),
    Prompts("Prompts"),
    Skills("Skills"),
}

private data class RuntimeCatalogSnapshot(
    val projects: List<Project>,
    val agents: List<AgentDefinition>,
    val prompts: List<Prompt>,
    val skills: List<AgentSkill>,
)

@Composable
fun AgentConstructorScreen(
    projectId: Project.Id?,
    projectService: ProjectDomainService,
    agentService: AgentDomainService,
    agentSkillService: AgentSkillDomainService,
    promptService: PromptDomainService,
    aiConfigurationService: AiConfigurationService,
    runtimeCatalogTemplateService: RuntimeCatalogTemplateService,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(RuntimeCatalogTab.Agents) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var selectedProjectId by remember { mutableStateOf(projectId) }
    var agents by remember { mutableStateOf<List<AgentDefinition>>(emptyList()) }
    var prompts by remember { mutableStateOf<List<Prompt>>(emptyList()) }
    var skills by remember { mutableStateOf<List<AgentSkill>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var editingAgent by remember { mutableStateOf<AgentDefinition?>(null) }
    var agentTemplate by remember { mutableStateOf<AgentTemplate?>(null) }
    var showAgentEditor by remember { mutableStateOf(false) }
    var deletingAgent by remember { mutableStateOf<AgentDefinition?>(null) }

    var editingPrompt by remember { mutableStateOf<Prompt?>(null) }
    var promptTemplate by remember { mutableStateOf<PromptTemplate?>(null) }
    var showPromptEditor by remember { mutableStateOf(false) }
    var viewingPrompt by remember { mutableStateOf<Prompt?>(null) }
    var deletingPrompt by remember { mutableStateOf<Prompt?>(null) }

    var viewingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var deletingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var templateMenuExpanded by remember { mutableStateOf(false) }
    var scopeMenuExpanded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val observedAiSnapshot by aiConfigurationService.snapshotFlow.collectAsState()
    val aiSnapshot = observedAiSnapshot ?: aiConfigurationService.snapshot
    val templates = remember { runtimeCatalogTemplateService.getTemplates() }
    val scopedAgents = agents.filter {
        if (selectedProjectId == null) it.type is AgentDefinition.Type.Global
        else it.projectId == selectedProjectId
    }
    val scopedPrompts = prompts.filter {
        if (selectedProjectId == null) it.type is Prompt.Type.Global
        else it.projectId == selectedProjectId
    }
    val availablePrompts = prompts.filter {
        it.type is Prompt.Type.Global || it.projectId == selectedProjectId
    }
    val availableAgentModels = aiSnapshot.catalog.modelConfigurations.filter {
        aiSnapshot.supportsPurpose(
            it,
            AiRuntimeAssignment.Purpose.DEFAULT_CHAT,
        )
    }

    LaunchedEffect(selectedProjectId, refreshKey) {
        isLoading = true
        combine(
            projectService.observeAll(),
            agentService.observeAll(),
            promptService.observeAll(),
            selectedProjectId?.let(agentSkillService::observeByProject) ?: flowOf(emptyList()),
            ::RuntimeCatalogSnapshot,
        ).catch { failure ->
            error = failure.message ?: "Failed to observe runtime catalog"
            isLoading = false
        }.collect { snapshot ->
            projects = snapshot.projects
            agents = snapshot.agents
            prompts = snapshot.prompts
            skills = snapshot.skills
            error = null
            isLoading = false
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Runtime catalog",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Agents and prompts are server-owned runtime configuration. Skills are imported packages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { scopeMenuExpanded = true }) {
                        Text(
                            selectedProjectId?.let { id ->
                                projects.firstOrNull { it.id == id }?.name ?: id.value
                            } ?: "Global"
                        )
                    }
                    DropdownMenu(
                        expanded = scopeMenuExpanded,
                        onDismissRequest = { scopeMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Global") },
                            onClick = {
                                selectedProjectId = null
                                scopeMenuExpanded = false
                            },
                        )
                        projects.forEach { project ->
                            DropdownMenuItem(
                                text = { Text(project.name) },
                                onClick = {
                                    selectedProjectId = project.id
                                    scopeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                CompactButton(
                    onClick = { refreshKey++ },
                    tooltip = "Reload runtime catalog",
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload")
                }
                if (selectedTab != RuntimeCatalogTab.Skills) {
                    Box {
                        OutlinedButton(onClick = { templateMenuExpanded = true }) {
                            Text("From template")
                        }
                        DropdownMenu(
                            expanded = templateMenuExpanded,
                            onDismissRequest = { templateMenuExpanded = false },
                        ) {
                            when (selectedTab) {
                                RuntimeCatalogTab.Agents -> templates.agents.forEach { template ->
                                    DropdownMenuItem(
                                        text = { Text(template.name) },
                                        onClick = {
                                            agentTemplate = template
                                            editingAgent = null
                                            showAgentEditor = true
                                            templateMenuExpanded = false
                                        },
                                    )
                                }

                                RuntimeCatalogTab.Prompts -> templates.prompts.forEach { template ->
                                    DropdownMenuItem(
                                        text = { Text(template.name) },
                                        onClick = {
                                            promptTemplate = template
                                            editingPrompt = null
                                            showPromptEditor = true
                                            templateMenuExpanded = false
                                        },
                                    )
                                }

                                RuntimeCatalogTab.Skills -> Unit
                            }
                        }
                    }
                }
                if (selectedTab != RuntimeCatalogTab.Skills) {
                    Button(
                        onClick = {
                            when (selectedTab) {
                                RuntimeCatalogTab.Agents -> {
                                    editingAgent = null
                                    agentTemplate = null
                                    showAgentEditor = true
                                }
                                RuntimeCatalogTab.Prompts -> {
                                    editingPrompt = null
                                    promptTemplate = null
                                    showPromptEditor = true
                                }
                                RuntimeCatalogTab.Skills -> Unit
                            }
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (selectedTab == RuntimeCatalogTab.Agents) "New agent" else "New prompt")
                    }
                }
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        SecondaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            RuntimeCatalogTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.title) },
                )
            }
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            selectedTab == RuntimeCatalogTab.Agents -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (scopedAgents.isEmpty()) {
                    item { EmptyCatalogMessage("No agents in this scope") }
                }
                items(scopedAgents, key = { it.id.value }) { agent ->
                    AgentListItem(
                        agent = agent,
                        isDefault = agent.id == aiSnapshot.catalog.defaultAgentId,
                        onEdit = {
                            editingAgent = agent
                            agentTemplate = null
                            showAgentEditor = true
                        },
                        onCopy = {
                            coroutineScope.launch {
                                runCatching {
                                    agentService.duplicateAgent(
                                        projectId = selectedProjectId,
                                        sourceAgentId = agent.id,
                                        name = "${agent.name} copy",
                                    )
                                }.onFailure { error = it.message }
                            }
                        },
                        onSetDefault = if (agent.type is AgentDefinition.Type.Global) {
                            {
                                coroutineScope.launch {
                                    runCatching {
                                        aiConfigurationService.replaceCatalog(
                                            aiSnapshot.catalog.copy(defaultAgentId = agent.id),
                                            aiSnapshot.revision,
                                        )
                                    }.onFailure { error = it.message }
                                }
                            }
                        } else {
                            null
                        },
                        onDelete = { deletingAgent = agent },
                    )
                }
            }

            selectedTab == RuntimeCatalogTab.Prompts -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (scopedPrompts.isEmpty()) {
                    item { EmptyCatalogMessage("No prompts in this scope") }
                }
                items(scopedPrompts, key = { it.id.value }) { prompt ->
                    PromptListItem(
                        prompt = prompt,
                        onView = { viewingPrompt = prompt },
                        onEdit = {
                            editingPrompt = prompt
                            promptTemplate = null
                            showPromptEditor = true
                        },
                        onDelete = { deletingPrompt = prompt },
                    )
                }
            }

            selectedTab == RuntimeCatalogTab.Skills -> SkillsCatalog(
                projectId = selectedProjectId,
                skills = skills,
                onView = { viewingSkill = it },
                onDelete = { deletingSkill = it },
            )
        }
    }

    if (showAgentEditor) {
        val templatePromptIds = agentTemplate?.promptTemplateIds.orEmpty().map {
            Prompt.Id("global:${it.value}")
        } + if (agentTemplate?.includeRuntimeEnvironment == true) listOf(Prompt.Id("env")) else emptyList()
        AgentEditorDialog(
            agent = editingAgent,
            template = agentTemplate,
            prompts = availablePrompts,
            skills = skills,
            modelConfigurations = availableAgentModels,
            initialPromptIds = templatePromptIds,
            defaultRuntimeSelection = aiSnapshot.catalog.runtimeSelectionFor(
                AiRuntimeAssignment.Purpose.DEFAULT_CHAT
            ) ?: error("Default chat runtime is not configured"),
            onSave = { value ->
                coroutineScope.launch {
                    runCatching {
                        val current = editingAgent
                        if (current == null) {
                            agentService.createAgent(
                                projectId = selectedProjectId,
                                name = value.name,
                                prompts = value.prompts,
                                runtimeSelection = value.runtimeSelection,
                                runtimeOverrides = value.runtimeOverrides,
                                tools = value.tools,
                                description = value.description,
                                skills = value.skills,
                            )
                        } else {
                            agentService.update(
                                id = current.id,
                                name = value.name,
                                prompts = value.prompts,
                                description = value.description,
                                skills = value.skills,
                                runtimeSelection = value.runtimeSelection,
                                runtimeOverrides = value.runtimeOverrides,
                                tools = value.tools,
                            )
                        }
                        showAgentEditor = false
                        editingAgent = null
                        agentTemplate = null
                    }.onFailure { error = it.message }
                }
            },
            onDismiss = {
                showAgentEditor = false
                editingAgent = null
                agentTemplate = null
            },
        )
    }

    if (showPromptEditor) {
        PromptEditorDialog(
            prompt = editingPrompt,
            template = promptTemplate,
            onSave = { name, content ->
                coroutineScope.launch {
                    runCatching {
                        editingPrompt?.let {
                            promptService.updatePrompt(it.id, name, content)
                        } ?: promptService.createPrompt(selectedProjectId, name, content)
                        showPromptEditor = false
                        editingPrompt = null
                        promptTemplate = null
                    }.onFailure { error = it.message }
                }
            },
            onDismiss = {
                showPromptEditor = false
                editingPrompt = null
                promptTemplate = null
            },
        )
    }

    viewingPrompt?.let { prompt ->
        PromptViewDialog(prompt = prompt, onDismiss = { viewingPrompt = null })
    }

    deletingAgent?.let { agent ->
        DeleteConfirmation(
            title = "Delete agent?",
            message = "Delete \"${agent.name}\"? Conversations or defaults that reference it will prevent deletion.",
            onDismiss = { deletingAgent = null },
            onConfirm = {
                coroutineScope.launch {
                    runCatching {
                        agentService.delete(agent.id)
                        deletingAgent = null
                    }.onFailure {
                        error = it.message
                        deletingAgent = null
                    }
                }
            },
        )
    }

    deletingPrompt?.let { prompt ->
        DeleteConfirmation(
            title = "Delete prompt?",
            message = "Delete \"${prompt.name}\"? Agents that reference it will prevent deletion.",
            onDismiss = { deletingPrompt = null },
            onConfirm = {
                coroutineScope.launch {
                    runCatching {
                        promptService.deletePrompt(prompt.id)
                        deletingPrompt = null
                    }.onFailure {
                        error = it.message
                        deletingPrompt = null
                    }
                }
            },
        )
    }

    viewingSkill?.let { skill ->
        AgentSkillDetailsDialog(skill = skill, onDismiss = { viewingSkill = null })
    }

    deletingSkill?.let { skill ->
        DeleteConfirmation(
            title = "Delete skill package?",
            message = "Delete \"${skill.name}\"? Assigned packages cannot be deleted.",
            onDismiss = { deletingSkill = null },
            onConfirm = {
                coroutineScope.launch {
                    runCatching {
                        agentSkillService.delete(skill.id)
                        deletingSkill = null
                    }.onFailure {
                        error = it.message
                        deletingSkill = null
                    }
                }
            },
        )
    }
}

@Composable
private fun SkillsCatalog(
    projectId: Project.Id?,
    skills: List<AgentSkill>,
    onView: (AgentSkill) -> Unit,
    onDelete: (AgentSkill) -> Unit,
) {
    if (projectId == null) {
        EmptyCatalogMessage("Skills are project-scoped packages. Select a project to manage them.")
        return
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Skill contents are not edited here. Importing the same package name updates it from disk.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (skills.isEmpty()) {
                item {
                    EmptyCatalogMessage(
                        "No skill packages. Workspace package import will populate this catalog."
                    )
                }
            }
            items(skills, key = { it.id.value }) { skill ->
                androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(skill.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                skill.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Package ${skill.contentHash.take(12)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onView(skill) }) {
                            Icon(Icons.Default.Visibility, contentDescription = "View package")
                        }
                        IconButton(onClick = { onDelete(skill) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete package",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCatalogMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeleteConfirmation(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
