package com.gromozeka.presentation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.presentation.ui.viewmodel.ConversationSearchViewModel
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.WorkspaceCatalogService
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KLoggers.logger("SessionListScreen")

private data class ProjectGroup(
    val project: Project,
    val workspaces: List<Workspace>,
    val conversations: List<Conversation>,
    val latestConversation: Conversation?,
    val formattedTime: String,
) {
    val projectId get() = project.id
    val projectName get() = project.name

    fun workspaceName(workspaceId: Workspace.Id): String =
        workspaces.firstOrNull { it.id == workspaceId }?.name ?: workspaceId.value
}

@Composable
fun SessionListScreen(
    onConversationSelected: (Conversation, Project) -> Unit,
    coroutineScope: CoroutineScope,
    onNewSession: (Project, Workspace) -> Unit,
    projectService: ProjectDomainService,
    workspaceCatalogService: WorkspaceCatalogService,
    conversationTreeService: ConversationDomainService,
    appViewModel: AppViewModel,
    searchViewModel: ConversationSearchViewModel,
    showSettingsPanel: Boolean,
    onShowSettingsPanelChange: (Boolean) -> Unit,
    refreshTrigger: Int = 0,
) {
    var projectGroups by remember { mutableStateOf<List<ProjectGroup>>(emptyList()) }
    var pinnedConversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var expandedProjects by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    var workspacePickerProjectId by remember { mutableStateOf<Project.Id?>(null) }
    var conversationToRename by remember { mutableStateOf<Conversation?>(null) }
    var mutatingConversationIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var operationError by remember { mutableStateOf<String?>(null) }

    val searchQuery by searchViewModel.searchQuery.collectAsState()
    val isSearching by searchViewModel.isSearching.collectAsState()
    val searchResults by searchViewModel.searchResults.collectAsState()
    val showSearchResults by searchViewModel.showSearchResults.collectAsState()

    suspend fun loadProjects(showLoading: Boolean = true) {
        if (showLoading) {
            isLoading = true
        }
        try {
            val recentProjects = projectService.findRecent(limit = 100)
            val pinned = conversationTreeService.findPinned()
            val recentProjectIds = recentProjects.mapTo(mutableSetOf()) { it.id }
            val pinnedProjects = pinned
                .map { it.projectId }
                .distinct()
                .filterNot { it in recentProjectIds }
                .map { projectId ->
                    projectService.findById(projectId)
                        ?: error("Pinned conversation project not found: ${projectId.value}")
                }
            val projects = recentProjects + pinnedProjects

            val groupedProjects = projects.map { project ->
                val workspaces = workspaceCatalogService.findByProject(project.id)
                val conversations = conversationTreeService.findByProject(project.id)
                val latestConversation = conversations.maxByOrNull { it.updatedAt }
                val formattedTime = latestConversation?.let { formatRelativeTime(it.updatedAt) } ?: ""

                ProjectGroup(
                    project = project,
                    workspaces = workspaces,
                    conversations = conversations,
                    latestConversation = latestConversation,
                    formattedTime = formattedTime
                )
            }

            projectGroups = groupedProjects.sortedByDescending { it.latestConversation?.updatedAt }
            pinnedConversations = pinned
            operationError = null

            log.info("Loaded ${projectGroups.sumOf { it.conversations.size }} conversations in ${projectGroups.size} projects")
        } catch (e: Exception) {
            log.warn(e) { "Error loading projects: ${e.message}" }
            operationError = e.message ?: "Failed to load conversations"
        } finally {
            if (showLoading) {
                isLoading = false
            }
        }
    }

    fun refreshSearchResults() {
        if (showSearchResults) {
            searchViewModel.performSearch()
        }
    }

    fun togglePinned(conversation: Conversation) {
        if (conversation.id.value in mutatingConversationIds) return

        coroutineScope.launch {
            mutatingConversationIds += conversation.id.value
            operationError = null
            try {
                conversationTreeService.setPinned(
                    conversationId = conversation.id,
                    pinned = conversation.pinnedAt == null,
                ) ?: error("Conversation not found: ${conversation.id.value}")
                loadProjects(showLoading = false)
                refreshSearchResults()
            } catch (e: Exception) {
                log.warn(e) { "Failed to update conversation pin: ${e.message}" }
                operationError = e.message ?: "Failed to update conversation pin"
            } finally {
                mutatingConversationIds -= conversation.id.value
            }
        }
    }

    fun renameConversation(conversation: Conversation, displayName: String) {
        if (conversation.id.value in mutatingConversationIds) return

        coroutineScope.launch {
            mutatingConversationIds += conversation.id.value
            operationError = null
            try {
                conversationTreeService.updateDisplayName(conversation.id, displayName)
                    ?: error("Conversation not found: ${conversation.id.value}")
                loadProjects(showLoading = false)
                refreshSearchResults()
            } catch (e: Exception) {
                log.warn(e) { "Failed to rename conversation: ${e.message}" }
                operationError = e.message ?: "Failed to rename conversation"
            } finally {
                mutatingConversationIds -= conversation.id.value
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        loadProjects()
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))

                CompactButton(
                    onClick = {
                        coroutineScope.launch {
                            loadProjects()
                        }
                    },
                    tooltip = LocalTranslation.current.refreshSessionsTooltip
                ) {
                    Text("🔄")
                }

                Spacer(modifier = Modifier.width(8.dp))

                CompactButton(
                    onClick = {
                        workspacePickerProjectId = null
                        showWorkspacePicker = true
                    }
                ) {
                    Text(LocalTranslation.current.newSessionButton)
                }

                Spacer(modifier = Modifier.width(8.dp))

                CompactButton(
                    onClick = { onShowSettingsPanelChange(!showSettingsPanel) },
                    tooltip = LocalTranslation.current.settingsTooltip
                ) {
                    Icon(Icons.Default.Settings, contentDescription = LocalTranslation.current.settingsTooltip)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            SearchPanel(
                query = searchQuery,
                onQueryChange = searchViewModel::updateSearchQuery,
                isSearching = isSearching,
                onSearch = searchViewModel::performSearch,
                onClear = searchViewModel::clearSearch
            )

            operationError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    val pinnedItems = pinnedConversations.mapNotNull { conversation ->
                        projectGroups
                            .firstOrNull { it.projectId == conversation.projectId }
                            ?.let { group -> conversation to group }
                    }
                    if (pinnedItems.isNotEmpty()) {
                        PinnedConversationsSection(
                            items = pinnedItems,
                            mutatingConversationIds = mutatingConversationIds,
                            onConversationClick = { conversation, project ->
                                coroutineScope.handleConversationClick(
                                    conversation,
                                    project,
                                    onConversationSelected,
                                    appViewModel,
                                )
                            },
                            onRename = { conversationToRename = it },
                            onTogglePinned = ::togglePinned,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (showSearchResults) {
                        if (searchResults.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSearching) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator()
                                        Text(
                                            text = LocalTranslation.current.searchingForText.format(searchQuery),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    Text(
                                        text = if (searchQuery.isBlank()) LocalTranslation.current.enterSearchQuery
                                        else LocalTranslation.current.nothingFoundForText.format(searchQuery),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = LocalTranslation.current.foundSessionsText.format(searchResults.size),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            searchResults.forEach { (conversation, project) ->
                                ConversationItem(
                                    conversation = conversation,
                                    project = project,
                                    onConversationClick = { clickedConversation, clickedProject ->
                                        coroutineScope.handleConversationClick(
                                            clickedConversation,
                                            clickedProject,
                                            onConversationSelected,
                                            appViewModel
                                        )
                                    },
                                    onRename = { conversationToRename = it },
                                    onTogglePinned = ::togglePinned,
                                    isMutating = conversation.id.value in mutatingConversationIds,
                                    isGrouped = false,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    } else {
                        if (projectGroups.isEmpty()) {
                            val emptyStateLines = LocalTranslation.current.noSavedProjectsText
                                .lines()
                                .map(String::trim)
                                .filter(String::isNotEmpty)

                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    emptyStateLines.firstOrNull()?.let { title ->
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )
                                    }

                                    emptyStateLines.drop(1).takeIf { it.isNotEmpty() }?.let { details ->
                                        Text(
                                            text = details.joinToString("\n"),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        } else {
                            projectGroups.forEach { group ->
                                ProjectGroupHeader(
                                    group = group,
                                    isExpanded = expandedProjects.contains(group.projectId.value),
                                    onToggleExpanded = {
                                        expandedProjects = if (expandedProjects.contains(group.projectId.value)) {
                                            expandedProjects - group.projectId.value
                                        } else {
                                            expandedProjects + group.projectId.value
                                        }
                                    },
                                    onNewSessionClick = {
                                        workspacePickerProjectId = group.project.id
                                        showWorkspacePicker = true
                                    },
                                    onConversationClick = { clickedConversation ->
                                        coroutineScope.handleConversationClick(
                                            clickedConversation,
                                            group.project,
                                            onConversationSelected,
                                            appViewModel
                                        )
                                    },
                                    onRename = { conversationToRename = it },
                                    onTogglePinned = ::togglePinned,
                                    mutatingConversationIds = mutatingConversationIds,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showWorkspacePicker) {
        LogicalWorkspacePickerDialog(
            projectGroups = projectGroups.filter { group ->
                workspacePickerProjectId == null || group.project.id == workspacePickerProjectId
            },
            onSelect = { project, workspace ->
                showWorkspacePicker = false
                onNewSession(project, workspace)
            },
            onDismiss = { showWorkspacePicker = false },
        )
    }

    conversationToRename?.let { conversation ->
        NameEditDialog(
            isOpen = true,
            currentName = conversation.displayName,
            title = LocalTranslation.current.renameConversationTitle,
            label = LocalTranslation.current.conversationNameLabel,
            maxLength = 255,
            onRename = { displayName -> renameConversation(conversation, displayName) },
            onDismiss = { conversationToRename = null },
        )
    }
}

@Composable
private fun PinnedConversationsSection(
    items: List<Pair<Conversation, ProjectGroup>>,
    mutatingConversationIds: Set<String>,
    onConversationClick: (Conversation, Project) -> Unit,
    onRename: (Conversation) -> Unit,
    onTogglePinned: (Conversation) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = LocalTranslation.current.pinnedConversationsTitle,
            style = MaterialTheme.typography.titleMedium,
        )
    }

    items.forEach { (conversation, group) ->
        ConversationItem(
            conversation = conversation,
            project = group.project,
            workspaceName = group.workspaceName(conversation.workspaceId),
            onConversationClick = onConversationClick,
            onRename = onRename,
            onTogglePinned = onTogglePinned,
            isMutating = conversation.id.value in mutatingConversationIds,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun ProjectGroupHeader(
    group: ProjectGroup,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onNewSessionClick: () -> Unit,
    onConversationClick: (Conversation) -> Unit,
    onRename: (Conversation) -> Unit,
    onTogglePinned: (Conversation) -> Unit,
    mutatingConversationIds: Set<String>,
) {
    CompactCard(
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = LocalTranslation.current.expandCollapseText,
                    modifier = Modifier.clickable { onToggleExpanded() }
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = group.projectName)
                    Text(text = "${group.workspaces.size} workspaces")
                    Text(text = "${group.conversations.size} conversations")
                }

                Spacer(modifier = Modifier.width(8.dp))

                CompactButton(onClick = onNewSessionClick, enabled = group.workspaces.isNotEmpty()) {
                    Text(LocalTranslation.current.newButton)
                }

                Spacer(modifier = Modifier.width(12.dp))

                group.latestConversation?.let { latestConversation ->
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = latestConversation.displayPreview())
                        // Message count removed - messages are loaded separately via ConversationService
                        Text(text = group.formattedTime)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    CompactButton(onClick = { onConversationClick(latestConversation) }) {
                        Text(LocalTranslation.current.continueButton)
                    }
                } ?: run {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = LocalTranslation.current.noSessionsText)
                        Text(text = "")
                        Text(text = "")
                    }
                }
            }

            if (isExpanded) {
                Column(
                    modifier = Modifier.padding(start = 32.dp, end = 12.dp, bottom = 8.dp)
                ) {
                    group.conversations.filter { it.pinnedAt == null }.forEach { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            project = group.project,
                            workspaceName = group.workspaceName(conversation.workspaceId),
                            onConversationClick = { clickedConversation, _ ->
                                onConversationClick(clickedConversation)
                            },
                            onRename = onRename,
                            onTogglePinned = onTogglePinned,
                            isMutating = conversation.id.value in mutatingConversationIds,
                            isGrouped = true,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    project: Project,
    workspaceName: String? = null,
    onConversationClick: (Conversation, Project) -> Unit,
    onRename: (Conversation) -> Unit,
    onTogglePinned: (Conversation) -> Unit,
    isMutating: Boolean,
    isGrouped: Boolean = false,
    modifier: Modifier = Modifier,
) {
    CompactCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.displayPreview(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = conversation.displayTime()
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isGrouped) {
                        Text(
                            text = workspaceName ?: project.name,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Text(
                            text = workspaceName ?: conversation.workspaceId.value,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Message count removed - messages are loaded separately via ConversationService
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (isMutating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                OptionalTooltip(
                    tooltip = if (conversation.pinnedAt == null) {
                        LocalTranslation.current.pinConversationTooltip
                    } else {
                        LocalTranslation.current.unpinConversationTooltip
                    },
                ) {
                    IconButton(onClick = { onTogglePinned(conversation) }) {
                        Icon(
                            imageVector = if (conversation.pinnedAt == null) {
                                Icons.Default.StarBorder
                            } else {
                                Icons.Default.Star
                            },
                            contentDescription = if (conversation.pinnedAt == null) {
                                LocalTranslation.current.pinConversationTooltip
                            } else {
                                LocalTranslation.current.unpinConversationTooltip
                            },
                            tint = if (conversation.pinnedAt == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }

                OptionalTooltip(tooltip = LocalTranslation.current.renameConversationTitle) {
                    IconButton(onClick = { onRename(conversation) }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = LocalTranslation.current.renameConversationTitle,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            CompactButton(
                onClick = { onConversationClick(conversation, project) },
                enabled = !isMutating,
            ) {
                Text(LocalTranslation.current.continueButton)
            }
        }
    }
}

private fun CoroutineScope.handleConversationClick(
    clickedConversation: Conversation,
    project: Project,
    onConversationSelected: (Conversation, Project) -> Unit,
    appViewModel: AppViewModel,
) {
    launch {
        try {
            val tabIndex = appViewModel.createTab(
                projectId = clickedConversation.projectId,
                workspaceId = clickedConversation.workspaceId,
                conversationId = clickedConversation.id,
                initiator = ConversationInitiator.System
            )
            appViewModel.selectTab(tabIndex)

            log.info("Created tab at index $tabIndex, resume conversation: ${clickedConversation.id}")

            onConversationSelected(clickedConversation, project)

        } catch (e: Exception) {
            log.warn(e) { "Failed to create tab: ${e.message}" }
        }
    }
}

@Composable
private fun LogicalWorkspacePickerDialog(
    projectGroups: List<ProjectGroup>,
    onSelect: (Project, Workspace) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose workspace") },
        text = {
            if (projectGroups.none { it.workspaces.isNotEmpty() }) {
                Text("No filesystem workspace is registered on a worker.")
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    projectGroups.forEach { group ->
                        if (group.workspaces.isNotEmpty()) {
                            Text(
                                text = group.project.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            group.workspaces.forEach { workspace ->
                                CompactButton(
                                    onClick = { onSelect(group.project, workspace) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(workspace.name)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
