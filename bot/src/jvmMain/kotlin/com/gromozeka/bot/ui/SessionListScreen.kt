package com.gromozeka.bot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.model.ProjectGroup
import com.gromozeka.bot.model.Session
import com.gromozeka.bot.services.ClaudeCodeStreamingWrapper
import com.gromozeka.bot.services.SessionJsonlService
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
@Composable
fun SessionListScreen(
    onSessionSelected: (ChatSession, List<ChatMessage>, Session) -> Unit,
    coroutineScope: CoroutineScope,
    onNewSession: (String) -> Unit,
    sessionJsonlService: SessionJsonlService,
    context: org.springframework.context.ConfigurableApplicationContext
) {
    var allSessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }
    var projectGroups by remember { mutableStateOf<List<ProjectGroup>>(emptyList()) }
    var expandedProjects by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Load sessions on first composition
    LaunchedEffect(Unit) {
        isLoading = true
        try {
            val loadedSessions = sessionJsonlService.loadAllSessions()
            allSessions = loadedSessions
            
            // Group sessions by project
            val groupedProjects = loadedSessions
                .groupBy { it.projectPath }
                .map { (projectPath, sessions) ->
                    val projectName = projectPath.substringAfterLast("/")
                    ProjectGroup(
                        projectPath = projectPath,
                        projectName = projectName,
                        sessions = sessions
                    )
                }
            
            projectGroups = groupedProjects.sortedByDescending { projectGroup ->
                projectGroup.sessionCount()
            }
                
            println("[SessionListScreen] Loaded ${loadedSessions.size} sessions in ${projectGroups.size} projects")
        } catch (e: Exception) {
            println("[SessionListScreen] Error loading sessions: ${e.message}")
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Выберите беседу", style = MaterialTheme.typography.h5)
        Spacer(modifier = Modifier.height(16.dp))

        // Временная упрощенная версия - только кнопка создания новой сессии
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
                if (projectGroups.isEmpty()) {
                    // No sessions found - show new session button
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Нет сохраненных сессий",
                                style = MaterialTheme.typography.h6,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(
                                onClick = { onNewSession("/Users/lewik/code/gromozeka/dev") }
                            ) {
                                Text("Создать новую сессию")
                            }
                        }
                    }
                } else {
                    // Show recent sessions first
                    val recentSessions = allSessions
                        .sortedByDescending { it.lastTimestamp }
                        .take(3)
                    
                    if (recentSessions.isNotEmpty()) {
                        Text(
                            text = "Последние сессии",
                            style = MaterialTheme.typography.h6,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        recentSessions.forEach { session ->
                            SessionItem(
                                session = session,
                                onSessionClick = { clickedSession ->
                                    coroutineScope.handleSessionClick(clickedSession, onSessionSelected, sessionJsonlService, context)
                                },
                                isGrouped = false
                            )
                        }
                        
                        Divider(
                            modifier = Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
                        )
                        
                        Text(
                            text = "Все проекты",
                            style = MaterialTheme.typography.h6,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    
                    // Show grouped sessions
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
                            onNewSessionClick = onNewSession
                        )
                        
                        if (expandedProjects.contains(group.projectPath)) {
                            // New session button first
                            NewSessionButton(
                                projectPath = group.projectPath,
                                onNewSessionClick = onNewSession
                            )
                            
                            // Then existing sessions
                            group.sessions.forEach { session ->
                                SessionItem(
                                    session = session,
                                    onSessionClick = { clickedSession ->
                                        coroutineScope.handleSessionClick(clickedSession, onSessionSelected, sessionJsonlService, context)
                                    },
                                    isGrouped = true
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
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f),
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Свернуть" else "Развернуть",
                modifier = Modifier.clickable { onToggleExpanded() }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = group.displayName(),
                style = MaterialTheme.typography.h6,
                modifier = Modifier.clickable { onToggleExpanded() }
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { onNewSessionClick(group.projectPath) },
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text("+ Новая")
            }
            Text(
                text = "${group.sessionCount()} сессий",
                style = MaterialTheme.typography.caption
            )
        }
    }
}

@Composable
private fun SessionItem(
    session: ChatSession,
    onSessionClick: (ChatSession) -> Unit,
    isGrouped: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isGrouped) 24.dp else 0.dp,
                top = 2.dp,
                bottom = 2.dp,
                end = 0.dp
            )
            .clickable { onSessionClick(session) },
        elevation = if (isGrouped) 1.dp else 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = session.displayPreview(),
                style = MaterialTheme.typography.body1
            )
            if (!isGrouped) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Проект: ${session.displayProject()}",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(
                    text = session.displayTime(),
                    style = MaterialTheme.typography.caption
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${session.messageCount} сообщений",
                    style = MaterialTheme.typography.caption
                )
            }
        }
    }
}

@Composable
private fun NewSessionButton(
    projectPath: String,
    onNewSessionClick: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 4.dp, bottom = 4.dp)
            .clickable { onNewSessionClick(projectPath) },
        backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "+ Новая сессия",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.primary
            )
        }
    }
}

// Resume existing session - create new Session with historical data loading
private fun CoroutineScope.handleSessionClick(
    clickedSession: ChatSession,
    onSessionSelected: (ChatSession, List<ChatMessage>, Session) -> Unit,
    sessionJsonlService: SessionJsonlService,
    context: org.springframework.context.ConfigurableApplicationContext
) {
    launch {
        try {
            // Create new Session that will load history during start
            val claudeWrapper = context.getBean(ClaudeCodeStreamingWrapper::class.java)
            val session = Session(clickedSession.projectPath, claudeWrapper, sessionJsonlService)
            
            // Pass session to parent - it will handle Claude process management  
            // The parent will call session.start() with resumeSessionId
            onSessionSelected(clickedSession, emptyList(), session)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}