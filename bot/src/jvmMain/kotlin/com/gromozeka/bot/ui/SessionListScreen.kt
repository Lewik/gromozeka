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
import com.gromozeka.bot.services.SessionJsonlService
import com.gromozeka.bot.services.SessionService
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SessionListScreen(
    onSessionSelected: (ChatSession, List<ChatMessage>, Session) -> Unit,
    coroutineScope: CoroutineScope,
    onNewSession: (String) -> Unit,
    sessionJsonlService: SessionJsonlService,
    context: org.springframework.context.ConfigurableApplicationContext,
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

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Выберите беседу")

        // Temporary simplified version - only new session button
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
                            Text(text = "Нет сохраненных сессий")
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
                        Text(text = "Последние сессии")

                        recentSessions.forEach { session ->
                            SessionItem(
                                session = session,
                                onSessionClick = { clickedSession ->
                                    coroutineScope.handleSessionClick(
                                        clickedSession,
                                        onSessionSelected,
                                        sessionJsonlService,
                                        context
                                    )
                                },
                                isGrouped = false
                            )
                        }

                        Divider()

                        Text(text = "Все проекты")
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
                                        coroutineScope.handleSessionClick(
                                            clickedSession,
                                            onSessionSelected,
                                            sessionJsonlService,
                                            context
                                        )
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
    Card {
        Row {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Свернуть" else "Развернуть",
                modifier = Modifier.clickable { onToggleExpanded() }
            )
            Text(
                text = group.displayName(),
                modifier = Modifier.clickable { onToggleExpanded() }
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { onNewSessionClick(group.projectPath) }) {
                Text("+ Новая")
            }
            Text(text = "${group.sessionCount()} сессий")
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
            .clickable { onSessionClick(session) }
    ) {
        Column {
            Text(text = session.displayPreview())
            if (!isGrouped) {
                Text(text = "Проект: ${session.displayProject()}")
            }
            Row {
                Text(text = session.displayTime())
                Spacer(modifier = Modifier.weight(1f))
                Text(text = "${session.messageCount} сообщений")
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
            .clickable { onNewSessionClick(projectPath) }
    ) {
        Row {
            Text(text = "+ Новая сессия")
        }
    }
}

// Resume existing session - create new Session with historical data loading
private fun CoroutineScope.handleSessionClick(
    clickedSession: ChatSession,
    onSessionSelected: (ChatSession, List<ChatMessage>, Session) -> Unit,
    sessionJsonlService: SessionJsonlService,
    context: org.springframework.context.ConfigurableApplicationContext,
) {
    launch {
        try {
            // Create new Session that will load history during start
            val sessionService = context.getBean(SessionService::class.java)
            val session = sessionService.createSession(clickedSession.projectPath)

            // Pass session to parent - it will handle Claude process management  
            // The parent will call session.start() with resumeSessionId
            onSessionSelected(clickedSession, emptyList(), session)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}