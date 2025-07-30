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
import com.gromozeka.bot.model.ChatMessage
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.model.ProjectGroup
import com.gromozeka.bot.services.ClaudeCodeSessionMapper
import com.gromozeka.bot.services.ClaudeCodeStreamingWrapper
import com.gromozeka.bot.services.SessionListService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SessionListScreen(
    sessionListService: SessionListService,
    onSessionSelected: (ChatSession, List<ChatMessage>) -> Unit,
    claudeCodeStreamingWrapper: ClaudeCodeStreamingWrapper,
    coroutineScope: CoroutineScope,
    onNewSession: (String) -> Unit,
) {
    var projectGroups by remember { mutableStateOf<List<ProjectGroup>>(emptyList()) }
    var expandedProjects by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            projectGroups = sessionListService.getSessionsGroupedByProject()
            expandedProjects = emptySet() // Projects collapsed by default
            isLoading = false
        } catch (e: Exception) {
            e.printStackTrace()
            isLoading = false
        }
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Выберите беседу", style = MaterialTheme.typography.h5)
        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (projectGroups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Нет доступных бесед")
            }
        } else {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth()) {
                // Recent sessions section
                val recentSessions = projectGroups.flatMap { it.sessions }
                    .sortedByDescending { it.lastTimestamp }
                    .take(3)
                
                if (recentSessions.isNotEmpty()) {
                    Text(
                        text = "Последние сессии",
                        style = MaterialTheme.typography.h6,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    
                    recentSessions.forEach { session ->
                        SessionItem(
                            session = session,
                            onSessionClick = { clickedSession ->
                                coroutineScope.launch {
                                    try {
                                        val encodedPath = clickedSession.projectPath.replace("/", "-")
                                        val sessionFile = File(
                                            System.getProperty("user.home"),
                                            ".claude/projects/$encodedPath/${clickedSession.sessionId}.jsonl"
                                        )

                                        val messages =
                                            ClaudeCodeSessionMapper.loadSessionAsChatMessages(sessionFile)

                                        claudeCodeStreamingWrapper.start(
                                            sessionId = clickedSession.sessionId,
                                            projectPath = clickedSession.projectPath
                                        )

                                        onSessionSelected(clickedSession, messages)

                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            },
                            isGrouped = false
                        )
                    }
                    
                    Divider(modifier = Modifier.padding(vertical = 16.dp))
                    
                    Text(
                        text = "Все проекты",
                        style = MaterialTheme.typography.h6,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                projectGroups.forEach { group ->
                    ProjectGroupHeader(
                        group = group,
                        isExpanded = group.projectPath in expandedProjects,
                        onToggleExpanded = {
                            expandedProjects = if (group.projectPath in expandedProjects) {
                                expandedProjects - group.projectPath
                            } else {
                                expandedProjects + group.projectPath
                            }
                        }
                    )

                    if (group.projectPath in expandedProjects) {
                        NewSessionButton(
                            projectPath = group.projectPath,
                            onNewSessionClick = onNewSession
                        )

                        group.sessions.forEach { session ->
                            SessionItem(
                                session = session,
                                onSessionClick = { clickedSession ->
                                    coroutineScope.launch {
                                        try {
                                            val encodedPath = clickedSession.projectPath.replace("/", "-")
                                            val sessionFile = File(
                                                System.getProperty("user.home"),
                                                ".claude/projects/$encodedPath/${clickedSession.sessionId}.jsonl"
                                            )

                                            val messages =
                                                ClaudeCodeSessionMapper.loadSessionAsChatMessages(sessionFile)

                                            claudeCodeStreamingWrapper.start(
                                                sessionId = clickedSession.sessionId,
                                                projectPath = clickedSession.projectPath
                                            )

                                            onSessionSelected(clickedSession, messages)

                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
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

@Composable
private fun ProjectGroupHeader(
    group: ProjectGroup,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onToggleExpanded() },
        backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f),
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Свернуть" else "Развернуть"
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = group.displayName(),
                style = MaterialTheme.typography.h6
            )
            Spacer(modifier = Modifier.weight(1f))
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