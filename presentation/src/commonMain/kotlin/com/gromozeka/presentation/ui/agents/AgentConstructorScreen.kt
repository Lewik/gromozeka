package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.presentation.ui.CompactButton
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KLoggers.logger("AgentConstructorScreen")

@Composable
fun AgentConstructorScreen(
    projectId: Project.Id?,
    agentService: AgentDomainService,
    agentSkillService: AgentSkillDomainService,
    promptService: PromptDomainService,
    settingsService: SettingsService,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    
    var agents by remember { mutableStateOf<List<AgentDefinition>>(emptyList()) }
    var prompts by remember { mutableStateOf<List<Prompt>>(emptyList()) }
    var skills by remember { mutableStateOf<List<AgentSkill>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Agent dialogs
    var showAgentEditorDialog by remember { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf<AgentDefinition?>(null) }
    var copyingAgent by remember { mutableStateOf<AgentDefinition?>(null) }
    var showAgentDeleteConfirmation by remember { mutableStateOf(false) }
    var deletingAgent by remember { mutableStateOf<AgentDefinition?>(null) }

    // Prompt dialogs
    var viewingPrompt by remember { mutableStateOf<Prompt?>(null) }
    var showPromptCreateDialog by remember { mutableStateOf(false) }

    var showSkillCreateDialog by remember { mutableStateOf(false) }
    var viewingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var deletingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    
    // Prompt filters
    var selectedPromptTypes by remember { mutableStateOf(setOf(0, 1)) }
    
    val filterOptions = remember {
        listOf(
            com.gromozeka.presentation.ui.ToggleButtonOption(Icons.Default.Lock, "Builtin"),
            com.gromozeka.presentation.ui.ToggleButtonOption(Icons.Default.Folder, "Project")
        )
    }
    
    val filteredPrompts = remember(prompts, selectedPromptTypes) {
        prompts.filter { prompt ->
            when (val type = prompt.type) {
                is Prompt.Type.Builtin -> 0 in selectedPromptTypes
                is Prompt.Type.Project -> 1 in selectedPromptTypes
            }
        }
    }

    suspend fun loadData() {
        isLoading = true
        error = null
        try {
            agents = projectId?.let { agentService.findByProject(it) }
                ?: agentService.findAll().filter { it.type is AgentDefinition.Type.Builtin }
            prompts = projectId?.let { promptService.findByProject(it) }
                ?: promptService.findAll().filter { it.type is Prompt.Type.Builtin }
            skills = projectId?.let { agentSkillService.findByProject(it) }.orEmpty()
            log.info { "Loaded ${agents.size} agents, ${prompts.size} prompts, and ${skills.size} skills" }
        } catch (e: Exception) {
            error = "Failed to load data: ${e.message}"
            log.error(e) { "Error loading data" }
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(projectId) {
        loadData()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Agent Constructor",
                style = MaterialTheme.typography.headlineMedium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(
                    onClick = {
                        coroutineScope.launch {
                            loadData()
                        }
                    },
                    tooltip = "Refresh list"
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }

                CompactButton(
                    onClick = {
                        when (selectedTab) {
                            0 -> {
                                editingAgent = null
                                copyingAgent = null
                                showAgentEditorDialog = true
                            }
                            1 -> {
                                showPromptCreateDialog = true
                            }
                            2 -> {
                                if (projectId == null) {
                                    error = "Open a project before creating an Agent Skill"
                                } else {
                                    showSkillCreateDialog = true
                                }
                            }
                        }
                    }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add new")
                        Text(
                            when (selectedTab) {
                                0 -> "New Agent"
                                1 -> "New Prompt"
                                else -> "New Skill"
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tab Row
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Agents") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Prompts") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Skills") }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // Filter buttons (only for Prompts tab)
        if (selectedTab == 1) {
            com.gromozeka.presentation.ui.ToggleButtonGroup(
                options = filterOptions,
                selectedIndices = selectedPromptTypes,
                onToggle = { index ->
                    selectedPromptTypes = if (index in selectedPromptTypes) {
                        selectedPromptTypes - index
                    } else {
                        selectedPromptTypes + index
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Content
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = { coroutineScope.launch { loadData() } }) {
                            Text("Retry")
                        }
                    }
                }
            }

            selectedTab == 0 -> {
                // Agents List
                if (agents.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "No agents yet",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    editingAgent = null
                                    copyingAgent = null
                                    showAgentEditorDialog = true
                                }
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "New Agent")
                                    Text("Create Agent")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(agents) { agent ->
                            AgentListItem(
                                agent = agent,
                                onEdit = {
                                    editingAgent = agent
                                    copyingAgent = null
                                    showAgentEditorDialog = true
                                },
                                onCopy = {
                                    editingAgent = null
                                    copyingAgent = agent
                                    showAgentEditorDialog = true
                                },
                                onDelete = {
                                    deletingAgent = agent
                                    showAgentDeleteConfirmation = true
                                }
                            )
                        }
                    }
                }
            }

            selectedTab == 1 -> {
                // Show prompt viewer or list
                if (viewingPrompt != null) {
                    // Full-screen prompt viewer
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Back button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompactButton(
                                onClick = { viewingPrompt = null }
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                    Text("Back to List")
                                }
                            }
                            
                            val isEditablePrompt = viewingPrompt!!.type !is Prompt.Type.Builtin
                            if (isEditablePrompt) {
                                CompactButton(
                                    onClick = {
                                        error = "Opening prompts in IDEA is disabled in the remote UI client"
                                    }
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                                        Text("Open in IDEA")
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Prompt title
                        Text(
                            text = viewingPrompt!!.name,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = when (val type = viewingPrompt!!.type) {
                                is Prompt.Type.Builtin -> "Built-in prompt"
                                is Prompt.Type.Project -> "Project prompt"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Markdown content
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            com.gromozeka.presentation.ui.GromozekaMarkdown(
                                content = viewingPrompt!!.content,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                } else if (filteredPrompts.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "No prompts yet",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = { showPromptCreateDialog = true }) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "New Prompt")
                                    Text("Create Prompt")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredPrompts) { prompt ->
                            val isEditablePrompt = prompt.type !is Prompt.Type.Builtin
                            
                            PromptListItem(
                                prompt = prompt,
                                onView = {
                                    viewingPrompt = prompt
                                },
                                onEdit = if (isEditablePrompt) {
                                    {
                                        error = "Opening prompts in IDEA is disabled in the remote UI client"
                                    }
                                } else null,
                                onDelete = null,
                            )
                        }
                    }
                }
            }

            selectedTab == 2 -> {
                if (projectId == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Open a project to manage Agent Skills",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (skills.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                "No Agent Skills yet",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { showSkillCreateDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "New Skill")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Create Skill")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(skills) { skill ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(skill.name, style = MaterialTheme.typography.titleMedium)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            skill.description,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { viewingSkill = skill }) {
                                        Text("View")
                                    }
                                    IconButton(onClick = { deletingSkill = skill }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Skill")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Agent Editor Dialog
    if (showAgentEditorDialog) {
        AgentEditorDialog(
            agent = editingAgent ?: copyingAgent,
            copyMode = copyingAgent != null,
            prompts = prompts,
            skills = skills,
            onSave = { name, selectedPrompts, selectedSkills, description ->
                coroutineScope.launch {
                    try {
                        val sourceAgent = copyingAgent
                        if (sourceAgent != null) {
                            val targetProjectId = checkNotNull(projectId) {
                                "Open a project conversation before copying an agent"
                            }
                            agentService.copyBuiltinAgent(
                                projectId = targetProjectId,
                                sourceAgentId = sourceAgent.id,
                                name = name,
                                prompts = selectedPrompts,
                                description = description,
                                skills = selectedSkills,
                            )
                            log.info { "Copied builtin agent ${sourceAgent.name} into project" }
                        } else if (editingAgent == null) {
                            val targetProjectId = checkNotNull(projectId) {
                                "Open a project conversation before creating an agent"
                            }
                            agentService.createAgent(
                                projectId = targetProjectId,
                                name = name,
                                prompts = selectedPrompts,
                                runtimeSelection = settingsService.runtimeSelectionFor(
                                    AiRuntimeAssignment.Purpose.DEFAULT_CHAT
                                ),
                                description = description,
                                skills = selectedSkills,
                            )
                            log.info { "Created new agent: $name" }
                        } else {
                            agentService.update(
                                id = editingAgent!!.id,
                                prompts = selectedPrompts,
                                description = description,
                                skills = selectedSkills,
                            )
                            log.info { "Updated agent: ${editingAgent!!.name}" }
                        }
                        loadData()
                        showAgentEditorDialog = false
                        editingAgent = null
                        copyingAgent = null
                    } catch (e: Exception) {
                        error = "Failed to save agent: ${e.message}"
                        log.error(e) { "Error saving agent" }
                    }
                }
            },
            onDismiss = {
                showAgentEditorDialog = false
                editingAgent = null
                copyingAgent = null
            }
        )
    }

    // Agent Delete Confirmation Dialog
    if (showAgentDeleteConfirmation && deletingAgent != null) {
        AlertDialog(
            onDismissRequest = {
                showAgentDeleteConfirmation = false
                deletingAgent = null
            },
            title = { Text("Delete Agent?") },
            text = {
                Text(
                    "Are you sure you want to delete \"${deletingAgent!!.name}\"?\n\n" +
                            "This action cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                agentService.delete(deletingAgent!!.id)
                                log.info { "Deleted agent: ${deletingAgent!!.name}" }
                                loadData()
                            } catch (e: Exception) {
                                error = "Failed to delete agent: ${e.message}"
                                log.error(e) { "Error deleting agent" }
                            }
                            showAgentDeleteConfirmation = false
                            deletingAgent = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAgentDeleteConfirmation = false
                        deletingAgent = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Prompt Create Dialog
    if (showPromptCreateDialog) {
        PromptCreateDialog(
            onSave = { name, content ->
                coroutineScope.launch {
                    try {
                        val targetProjectId = checkNotNull(projectId) {
                            "Open a project conversation before creating a prompt"
                        }
                        promptService.createProjectPrompt(targetProjectId, name, content)
                        log.info { "Created project prompt: $name" }
                        loadData()
                        showPromptCreateDialog = false
                    } catch (e: Exception) {
                        error = "Failed to create prompt: ${e.message}"
                        log.error(e) { "Error creating prompt" }
                    }
                }
            },
            onDismiss = {
                showPromptCreateDialog = false
            }
        )
    }

    if (showSkillCreateDialog) {
        AgentSkillCreateDialog(
            onSave = { name, description, instructions ->
                coroutineScope.launch {
                    try {
                        val targetProjectId = checkNotNull(projectId) {
                            "Open a project before creating an Agent Skill"
                        }
                        agentSkillService.importPackage(
                            projectId = targetProjectId,
                            source = AgentSkillPackageSource(
                                directoryName = name,
                                files = listOf(
                                    AgentSkillFile(
                                        path = "SKILL.md",
                                        content = buildAgentSkillMarkdown(
                                            name = name,
                                            description = description,
                                            instructions = instructions,
                                        ).encodeToByteArray(),
                                    )
                                ),
                            ),
                        )
                        loadData()
                        showSkillCreateDialog = false
                    } catch (e: Exception) {
                        error = "Failed to create Agent Skill: ${e.message}"
                        log.error(e) { "Error creating Agent Skill" }
                    }
                }
            },
            onDismiss = { showSkillCreateDialog = false },
        )
    }

    viewingSkill?.let { skill ->
        AgentSkillDetailsDialog(
            skill = skill,
            onDismiss = { viewingSkill = null },
        )
    }

    deletingSkill?.let { skill ->
        AlertDialog(
            onDismissRequest = { deletingSkill = null },
            title = { Text("Delete Agent Skill?") },
            text = { Text("Delete \"${skill.name}\"? Assigned skills cannot be deleted.") },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                agentSkillService.delete(skill.id)
                                loadData()
                                deletingSkill = null
                            } catch (e: Exception) {
                                error = "Failed to delete Agent Skill: ${e.message}"
                                deletingSkill = null
                                log.error(e) { "Error deleting Agent Skill" }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSkill = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun buildAgentSkillMarkdown(
    name: String,
    description: String,
    instructions: String,
): String =
    buildString {
        appendLine("---")
        append("name: ")
        appendLine(name)
        appendLine("description: |-")
        description.lineSequence().forEach { line ->
            append("  ")
            appendLine(line)
        }
        appendLine("---")
        appendLine(instructions)
    }
