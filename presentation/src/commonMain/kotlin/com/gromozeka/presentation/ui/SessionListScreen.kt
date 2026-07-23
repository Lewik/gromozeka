package com.gromozeka.presentation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.gromozeka.domain.service.ConversationDomainService
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KLoggers.logger("SessionListScreen")

private data class ProjectGroup(
    val project: Project,
    val conversationIds: List<Conversation.Id>,
    val latestConversationId: Conversation.Id?,
    val formattedTime: String,
) {
    val projectId get() = project.id
    val projectName get() = project.name
}

@Composable
fun SessionListScreen(
    onConversationSelected: (Conversation, Project) -> Unit,
    coroutineScope: CoroutineScope,
    onNewSession: (Project) -> Unit,
    projectService: ProjectDomainService,
    conversationTreeService: ConversationDomainService,
    appViewModel: AppViewModel,
    searchViewModel: ConversationSearchViewModel,
    showSettingsPanel: Boolean,
    onShowSettingsPanelChange: (Boolean) -> Unit,
    onManageProjects: () -> Unit,
    onManageWorkspaces: () -> Unit,
    refreshTrigger: Int = 0,
) {
    var projectGroups by remember { mutableStateOf<List<ProjectGroup>>(emptyList()) }
    var expandedProjects by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var conversationToRename by remember { mutableStateOf<Conversation?>(null) }
    var mutatingConversationIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var operationError by remember { mutableStateOf<String?>(null) }

    val searchQuery by searchViewModel.searchQuery.collectAsState()
    val isSearching by searchViewModel.isSearching.collectAsState()
    val searchResults by searchViewModel.searchResults.collectAsState()
    val showSearchResults by searchViewModel.showSearchResults.collectAsState()
    val conversationsById by appViewModel.conversations.collectAsState()
    val currentSearchResults = searchResults.map { (conversation, project) ->
        (conversationsById[conversation.id] ?: conversation) to project
    }

    suspend fun loadProjects(showLoading: Boolean = true) {
        if (showLoading) {
            isLoading = true
        }
        try {
            val projects = projectService.findRecent(limit = 100)

            val loadedConversations = mutableListOf<Conversation>()
            val groupedProjects = projects.map { project ->
                val conversations = conversationTreeService.findByProject(project.id)
                loadedConversations += conversations
                val latestConversation = conversations.maxByOrNull { it.updatedAt }
                val formattedTime = latestConversation?.let { formatRelativeTime(it.updatedAt) } ?: ""

                ProjectGroup(
                    project = project,
                    conversationIds = conversations.map(Conversation::id),
                    latestConversationId = latestConversation?.id,
                    formattedTime = formattedTime
                )
            }

            appViewModel.mergeConversationSnapshots(loadedConversations)
            projectGroups = groupedProjects.sortedByDescending { group ->
                group.latestConversationId?.let { appViewModel.conversations.value[it]?.updatedAt }
            }
            operationError = null

            log.info("Loaded ${projectGroups.sumOf { it.conversationIds.size }} conversations in ${projectGroups.size} projects")
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

    fun renameConversation(conversation: Conversation, displayName: String) {
        if (conversation.id.value in mutatingConversationIds) return

        coroutineScope.launch {
            mutatingConversationIds += conversation.id.value
            operationError = null
            try {
                appViewModel.renameConversation(conversation.id, displayName)
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

    LaunchedEffect(searchResults) {
        appViewModel.mergeConversationSnapshots(searchResults.map { it.first })
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactButton(onClick = onManageProjects) {
                    Text("Projects")
                }

                Spacer(modifier = Modifier.width(8.dp))

                CompactButton(onClick = onManageWorkspaces) {
                    Text("Workspaces")
                }

                Spacer(modifier = Modifier.weight(1f))

                CompactButton(
                    onClick = {
                        coroutineScope.launch {
                            loadProjects()
                        }
                    },
                    tooltip = LocalTranslation.current.refreshSessionsTooltip
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = LocalTranslation.current.refreshSessionsTooltip)
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
                    if (showSearchResults) {
                        if (currentSearchResults.isEmpty()) {
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
                            currentSearchResults.forEach { (conversation, project) ->
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
                                    conversationsById = conversationsById,
                                    isExpanded = expandedProjects.contains(group.projectId.value),
                                    onToggleExpanded = {
                                        expandedProjects = if (expandedProjects.contains(group.projectId.value)) {
                                            expandedProjects - group.projectId.value
                                        } else {
                                            expandedProjects + group.projectId.value
                                        }
                                    },
                                    onNewSessionClick = { onNewSession(group.project) },
                                    onConversationClick = { clickedConversation ->
                                        coroutineScope.handleConversationClick(
                                            clickedConversation,
                                            group.project,
                                            onConversationSelected,
                                            appViewModel
                                        )
                                    },
                                    onRename = { conversationToRename = it },
                                    mutatingConversationIds = mutatingConversationIds,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    conversationToRename?.let { conversation ->
        val currentConversation = conversationsById[conversation.id] ?: conversation
        NameEditDialog(
            isOpen = true,
            currentName = currentConversation.displayName,
            title = LocalTranslation.current.renameConversationTitle,
            label = LocalTranslation.current.conversationNameLabel,
            maxLength = 255,
            onRename = { displayName -> renameConversation(currentConversation, displayName) },
            onDismiss = { conversationToRename = null },
        )
    }
}

@Composable
private fun ProjectGroupHeader(
    group: ProjectGroup,
    conversationsById: Map<Conversation.Id, Conversation>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onNewSessionClick: () -> Unit,
    onConversationClick: (Conversation) -> Unit,
    onRename: (Conversation) -> Unit,
    mutatingConversationIds: Set<String>,
) {
    val conversations = group.conversationIds.mapNotNull(conversationsById::get)
    val latestConversation = group.latestConversationId?.let(conversationsById::get)

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
                    Text(text = "${group.conversationIds.size} conversations")
                }

                Spacer(modifier = Modifier.width(8.dp))

                CompactButton(onClick = onNewSessionClick) {
                    Text(LocalTranslation.current.newButton)
                }

                Spacer(modifier = Modifier.width(12.dp))

                latestConversation?.let {
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
                    conversations.forEach { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            project = group.project,
                            onConversationClick = { clickedConversation, _ ->
                                onConversationClick(clickedConversation)
                            },
                            onRename = onRename,
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
    onConversationClick: (Conversation, Project) -> Unit,
    onRename: (Conversation) -> Unit,
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

                if (!isGrouped) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = project.name,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (isMutating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
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
