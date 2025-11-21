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
import com.gromozeka.domain.repository.AgentDomainService
import com.gromozeka.domain.repository.PromptDomainService
import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.model.Prompt
import com.gromozeka.presentation.ui.CompactButton
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KLoggers.logger("AgentConstructorScreen")

@Composable
fun AgentConstructorScreen(
    agentService: AgentDomainService,
    promptService: PromptDomainService,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) } // 0 = Agents, 1 = Prompts
    
    var agents by remember { mutableStateOf<List<Agent>>(emptyList()) }
    var prompts by remember { mutableStateOf<List<Prompt>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Agent dialogs
    var showAgentEditorDialog by remember { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf<Agent?>(null) }
    var showAgentDeleteConfirmation by remember { mutableStateOf(false) }
    var deletingAgent by remember { mutableStateOf<Agent?>(null) }

    // Prompt dialogs
    var viewingPrompt by remember { mutableStateOf<Prompt?>(null) }
    var showPromptCreateDialog by remember { mutableStateOf(false) }
    
    // Prompt filters
    var selectedPromptTypes by remember { mutableStateOf(setOf(0, 1, 2)) } // All types selected by default
    
    val filterOptions = remember {
        listOf(
            com.gromozeka.presentation.ui.ToggleButtonOption(Icons.Default.Lock, "Builtin"),
            com.gromozeka.presentation.ui.ToggleButtonOption(Icons.Default.Folder, "User"),
            com.gromozeka.presentation.ui.ToggleButtonOption(Icons.Default.Description, "Other")
        )
    }
    
    val filteredPrompts = remember(prompts, selectedPromptTypes) {
        prompts.filter { prompt ->
            when (prompt.source) {
                is Prompt.Source.Builtin -> 0 in selectedPromptTypes
                is Prompt.Source.LocalFile.User -> 1 in selectedPromptTypes
                is Prompt.Source.Text,
                is Prompt.Source.LocalFile.Imported,
                is Prompt.Source.Remote.Url,
                is Prompt.Source.Dynamic -> 2 in selectedPromptTypes
            }
        }
    }

    suspend fun loadData() {
        isLoading = true
        error = null
        try {
            agents = agentService.findAll()
            prompts = promptService.findAll()
            log.info { "Loaded ${agents.size} agents and ${prompts.size} prompts" }
        } catch (e: Exception) {
            error = "Failed to load data: ${e.message}"
            log.error(e) { "Error loading data" }
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
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
                                showAgentEditorDialog = true
                            }
                            1 -> {
                                showPromptCreateDialog = true
                            }
                        }
                    }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add new")
                        Text(if (selectedTab == 0) "New Agent" else "New Prompt")
                    }
                }
                
                // Prompts tab buttons
                if (selectedTab == 1) {
                    CompactButton(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    val result = promptService.importAllClaudeMd()
                                    result.fold(
                                        onSuccess = { count ->
                                            log.info { "Imported $count CLAUDE.md files" }
                                            promptService.refresh()
                                            loadData()
                                        },
                                        onFailure = { e ->
                                            error = "Failed to import: ${e.message}"
                                            log.error(e) { "Error importing CLAUDE.md files" }
                                        }
                                    )
                                } catch (e: Exception) {
                                    error = "Failed to import: ${e.message}"
                                    log.error(e) { "Error importing CLAUDE.md files" }
                                }
                            }
                        },
                        tooltip = "Find and import all CLAUDE.md files from disk"
                    ) {
                        Text("Find all CLAUDE.md")
                    }
                    
                    CompactButton(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    val result = promptService.resetAllBuiltinPrompts()
                                    result.fold(
                                        onSuccess = { count ->
                                            log.info { "Reset $count builtin prompts to user directory" }
                                            promptService.refresh()
                                            loadData()
                                        },
                                        onFailure = { e ->
                                            error = "Failed to reset prompts: ${e.message}"
                                            log.error(e) { "Error resetting prompts" }
                                        }
                                    )
                                } catch (e: Exception) {
                                    error = "Failed to reset prompts: ${e.message}"
                                    log.error(e) { "Error resetting prompts" }
                                }
                            }
                        }
                    ) {
                        Text("Reset All")
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
                            
                            val isFilePrompt = viewingPrompt!!.source is Prompt.Source.LocalFile
                            if (isFilePrompt) {
                                CompactButton(
                                    onClick = {
                                        val fullPath = when (val source = viewingPrompt!!.source) {
                                            is Prompt.Source.LocalFile.User -> {
                                                val gromozekaHome = System.getProperty("GROMOZEKA_HOME") ?: ""
                                                "$gromozekaHome/prompts/${source.path.value}"
                                            }
                                            is Prompt.Source.LocalFile -> source.path.value
                                            else -> null
                                        }
                                        fullPath?.let {
                                            try {
                                                ProcessBuilder("idea", it).start()
                                                log.info { "Opened prompt in IDEA: $it" }
                                            } catch (e: Exception) {
                                                error = "Failed to open IDEA: ${e.message}"
                                                log.error(e) { "Error opening IDEA" }
                                            }
                                        }
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
                            text = when (val source = viewingPrompt!!.source) {
                                is Prompt.Source.Builtin -> "Built-in prompt"
                                is Prompt.Source.LocalFile.User -> "User prompt: ${source.path.value}"
                                is Prompt.Source.LocalFile.Imported -> "Imported: ${source.path.value}"
                                is Prompt.Source.Remote.Url -> "URL: ${source.url}"
                                is Prompt.Source.Dynamic.Environment -> "Environment info (generated dynamically)"
                                is Prompt.Source.Text -> "Inline prompt"
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
                            val isBuiltinPrompt = prompt.source is Prompt.Source.Builtin
                            val isFilePrompt = prompt.source is Prompt.Source.LocalFile
                            val isInlinePrompt = prompt.source is Prompt.Source.Text
                            
                            PromptListItem(
                                prompt = prompt,
                                onView = {
                                    viewingPrompt = prompt
                                },
                                onEdit = if (isFilePrompt) {
                                    {
                                        // Open in IDEA with full path
                                        val fullPath = when (val source = prompt.source) {
                                            is Prompt.Source.LocalFile.User -> {
                                                val gromozekaHome = System.getProperty("GROMOZEKA_HOME") ?: ""
                                                "$gromozekaHome/prompts/${source.path.value}"
                                            }
                                            is Prompt.Source.LocalFile -> source.path.value
                                            else -> null
                                        }
                                        fullPath?.let {
                                            try {
                                                ProcessBuilder("idea", it).start()
                                                log.info { "Opened prompt in IDEA: $it" }
                                            } catch (e: Exception) {
                                                error = "Failed to open IDEA: ${e.message}"
                                                log.error(e) { "Error opening IDEA" }
                                            }
                                        }
                                    }
                                } else null,
                                onDelete = if (isInlinePrompt) {
                                    {
                                        // TODO: Implement inline prompt deletion
                                        log.warn { "Inline prompt deletion not yet implemented" }
                                    }
                                } else null,
                                onCopyToUser = if (isBuiltinPrompt) {
                                    {
                                        coroutineScope.launch {
                                            try {
                                                val result = promptService.copyBuiltinPromptToUser(prompt.id)
                                                result.fold(
                                                    onSuccess = {
                                                        log.info { "Copied builtin prompt to user: ${prompt.name}" }
                                                        promptService.refresh() // Re-scan file system
                                                        loadData() // Refresh UI
                                                    },
                                                    onFailure = { e ->
                                                        error = "Failed to copy prompt: ${e.message}"
                                                        log.error(e) { "Error copying prompt" }
                                                    }
                                                )
                                            } catch (e: Exception) {
                                                error = "Failed to copy prompt: ${e.message}"
                                                log.error(e) { "Error copying prompt" }
                                            }
                                        }
                                    }
                                } else null
                            )
                        }
                    }
                }
            }
        }
    }

    // Agent Editor Dialog
    if (showAgentEditorDialog) {
        AgentEditorDialog(
            agent = editingAgent,
            prompts = prompts,
            onSave = { name, selectedPrompts, description ->
                coroutineScope.launch {
                    try {
                        if (editingAgent == null) {
                            agentService.createAgent(
                                name = name,
                                prompts = selectedPrompts,
                                description = description,
                                isBuiltin = false
                            )
                            log.info { "Created new agent: $name" }
                        } else {
                            agentService.update(
                                id = editingAgent!!.id,
                                prompts = selectedPrompts,
                                description = description
                            )
                            log.info { "Updated agent: ${editingAgent!!.name}" }
                        }
                        loadData()
                        showAgentEditorDialog = false
                    } catch (e: Exception) {
                        error = "Failed to save agent: ${e.message}"
                        log.error(e) { "Error saving agent" }
                    }
                }
            },
            onDismiss = {
                showAgentEditorDialog = false
                editingAgent = null
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
                        promptService.createInlinePrompt(name, content)
                        log.info { "Created inline prompt: $name" }
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
}
