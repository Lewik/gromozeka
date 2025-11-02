package com.gromozeka.bot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gromozeka.shared.services.ProjectService
import com.gromozeka.bot.ui.viewmodel.AppViewModel
import com.gromozeka.bot.ui.state.ConversationInitiator
import com.gromozeka.bot.ui.viewmodel.ConversationSearchViewModel
import com.gromozeka.shared.domain.Project
import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.services.ConversationService
import klog.KLoggers
import io.github.vinceglb.filekit.compose.rememberDirectoryPickerLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KLoggers.logger("SessionListScreen")

private data class ProjectGroup(
    val project: Project,
    val conversations: List<Conversation>,
    val latestConversation: Conversation?,
    val formattedTime: String,
) {
    val projectPath get() = project.path
    val projectName get() = project.displayName()
}

@Composable
fun SessionListScreen(
    onConversationSelected: (Conversation, Project) -> Unit,
    coroutineScope: CoroutineScope,
    onNewSession: (String) -> Unit,
    projectService: ProjectService,
    conversationTreeService: ConversationService,
    appViewModel: AppViewModel,
    searchViewModel: ConversationSearchViewModel,
    showSettingsPanel: Boolean,
    onShowSettingsPanelChange: (Boolean) -> Unit,
    refreshTrigger: Int = 0,
) {
    var projectGroups by remember { mutableStateOf<List<ProjectGroup>>(emptyList()) }
    var expandedProjects by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    val searchQuery by searchViewModel.searchQuery.collectAsState()
    val isSearching by searchViewModel.isSearching.collectAsState()
    val searchResults by searchViewModel.searchResults.collectAsState()
    val showSearchResults by searchViewModel.showSearchResults.collectAsState()

    val directoryPicker = rememberDirectoryPickerLauncher { directory ->
        directory?.let { dir ->
            dir.path?.let { path ->
                onNewSession(path)
            }
        }
    }

    suspend fun loadProjects() {
        isLoading = true
        try {
            val projects = projectService.findRecent(limit = 100)

            val groupedProjects = projects.map { project ->
                val conversations = conversationTreeService.findByProject(project.path)
                val latestConversation = conversations.maxByOrNull { it.updatedAt }
                val formattedTime = latestConversation?.let { formatRelativeTime(it.updatedAt) } ?: ""

                ProjectGroup(
                    project = project,
                    conversations = conversations,
                    latestConversation = latestConversation,
                    formattedTime = formattedTime
                )
            }

            projectGroups = groupedProjects.sortedByDescending { it.latestConversation?.updatedAt }

            log.info("Loaded ${projectGroups.sumOf { it.conversations.size }} conversations in ${projectGroups.size} projects")
        } catch (e: Exception) {
            log.warn(e) { "Error loading projects: ${e.message}" }
        } finally {
            isLoading = false
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
                    onClick = { directoryPicker.launch() }
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
                                    isGrouped = false,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    } else {
                        if (projectGroups.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = LocalTranslation.current.noSavedProjectsText,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            projectGroups.forEach { group ->
                                ProjectGroupHeader(
                                    group = group,
                                    isExpanded = expandedProjects.contains(group.projectPath),
                                    onToggleExpanded = {
                                        expandedProjects = if (expandedProjects.contains(group.projectPath)) {
                                            expandedProjects - group.projectPath
                                        } else {
                                            expandedProjects + group.projectPath
                                        }
                                    },
                                    onNewSessionClick = onNewSession,
                                    onConversationClick = { clickedConversation ->
                                        coroutineScope.handleConversationClick(
                                            clickedConversation,
                                            group.project,
                                            onConversationSelected,
                                            appViewModel
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGroupHeader(
    group: ProjectGroup,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onNewSessionClick: (String) -> Unit,
    onConversationClick: (Conversation) -> Unit,
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
                    Text(text = group.projectPath)
                    Text(text = "${group.conversations.size} conversations")
                }

                Spacer(modifier = Modifier.width(8.dp))

                CompactButton(onClick = { onNewSessionClick(group.projectPath) }) {
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
                    group.conversations.forEach { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            project = group.project,
                            onConversationClick = { clickedConversation, _ ->
                                onConversationClick(clickedConversation)
                            },
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
                            text = project.path,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    // Message count removed - messages are loaded separately via ConversationService
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            CompactButton(
                onClick = { onConversationClick(conversation, project) }
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
                projectPath = project.path,
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
