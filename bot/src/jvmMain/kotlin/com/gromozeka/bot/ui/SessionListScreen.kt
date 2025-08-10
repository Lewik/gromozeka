package com.gromozeka.bot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.model.ChatSession
import com.gromozeka.bot.model.Session
import com.gromozeka.bot.services.SessionJsonlService
import com.gromozeka.bot.services.SessionService
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import io.github.vinceglb.filekit.compose.rememberDirectoryPickerLauncher

private data class ProjectGroup(
    val projectPath: String,
    val projectName: String,
    val sessions: List<ChatSession>,
    val latestSession: ChatSession?,
    val formattedTime: String,
)

private fun formatRelativeTime(timestamp: Instant): String {
    val now = kotlinx.datetime.Clock.System.now()
    val duration = now - timestamp
    return when {
        duration.inWholeMinutes < 1 -> "сейчас"
        duration.inWholeMinutes < 60 -> "${duration.inWholeMinutes}м назад"
        duration.inWholeHours < 24 -> "${duration.inWholeHours}ч назад"
        duration.inWholeDays < 7 -> "${duration.inWholeDays}д назад"
        else -> timestamp.toString().substring(0, 16).replace('T', ' ')
    }
}

@Composable
fun SessionListScreen(
    onSessionSelected: (ChatSession, List<ChatMessage>, Session) -> Unit,
    coroutineScope: CoroutineScope,
    onNewSession: (String) -> Unit,
    sessionJsonlService: SessionJsonlService,
    context: org.springframework.context.ConfigurableApplicationContext,
    // Settings support
    settings: com.gromozeka.bot.settings.Settings,
    onSettingsChange: (com.gromozeka.bot.settings.Settings) -> Unit,
    showSettingsPanel: Boolean,
    onShowSettingsPanelChange: (Boolean) -> Unit,
) {
    var projectGroups by remember { mutableStateOf<List<ProjectGroup>>(emptyList()) }
    var expandedProjects by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Directory picker for new sessions
    val directoryPicker = rememberDirectoryPickerLauncher { directory ->
        directory?.let { dir ->
            dir.path?.let { path ->
                onNewSession(path)
            }
        }
    }

    // Load sessions on first composition
    LaunchedEffect(Unit) {
        isLoading = true
        try {
            val loadedSessions = sessionJsonlService.loadAllSessions()

            // Group sessions by project
            val groupedProjects = loadedSessions
                .groupBy { it.projectPath }
                .map { (projectPath, sessions) ->
                    val projectName = projectPath.substringAfterLast("/")
                    val latestSession = sessions.maxByOrNull { it.lastTimestamp }
                    val formattedTime = latestSession?.let { formatRelativeTime(it.lastTimestamp) } ?: ""
                    ProjectGroup(
                        projectPath = projectPath,
                        projectName = projectName,
                        sessions = sessions,
                        latestSession = latestSession,
                        formattedTime = formattedTime
                    )
                }

            projectGroups = groupedProjects.sortedByDescending { it.latestSession?.lastTimestamp }

            println("[SessionListScreen] Loaded ${loadedSessions.size} sessions in ${projectGroups.size} projects")
        } catch (e: Exception) {
            println("[SessionListScreen] Error loading sessions: ${e.message}")
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Main content
        Column(modifier = Modifier.weight(1f)) {
            // Header with buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                
                CompactButton(
                    onClick = { directoryPicker.launch() }
                ) {
                    Text("Новая сессия")
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // Settings button
                CompactButton(
                    onClick = { onShowSettingsPanelChange(!showSettingsPanel) },
                    tooltip = "Настройки"
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
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
                    if (projectGroups.isEmpty()) {
                        // No sessions found - show message
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Нет сохраненных проектов\nНажмите \"Новая сессия\" для начала работы",
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // Show grouped sessions sorted by last activity
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
                                onSessionClick = { clickedSession ->
                                    coroutineScope.handleSessionClick(
                                        clickedSession,
                                        onSessionSelected,
                                        sessionJsonlService,
                                        context
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // Settings panel
        SettingsPanel(
            isVisible = showSettingsPanel,
            settings = settings,
            onSettingsChange = onSettingsChange,
            onClose = { onShowSettingsPanelChange(false) }
        )
    }
}

@Composable
private fun ProjectGroupHeader(
    group: ProjectGroup,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onNewSessionClick: (String) -> Unit,
    onSessionClick: (ChatSession) -> Unit,
) {
    CompactCard(
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Кнопка раскрытия
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Свернуть" else "Развернуть",
                    modifier = Modifier.clickable { onToggleExpanded() }
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // 2. Column 1 - информация о проекте
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = group.projectName)
                    Text(text = group.projectPath)
                    Text(text = "${group.sessions.size} сессий")
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // 3. Кнопка "Новая"
                CompactButton(onClick = { onNewSessionClick(group.projectPath) }) {
                    Text("Новая")
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                // 4. Column 2 - информация о последней сессии
                group.latestSession?.let { latestSession ->
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = latestSession.displayPreview())
                        Text(text = "${latestSession.messageCount} сообщений")
                        Text(text = group.formattedTime)
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // 5. Кнопка "Продолжить"
                    CompactButton(onClick = { onSessionClick(latestSession) }) {
                        Text("Продолжить")
                    }
                } ?: run {
                    // Если нет сессий - пустой Column
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Нет сессий")
                        Text(text = "")
                        Text(text = "")
                    }
                }
            }
            
            // Expanded content
            if (isExpanded) {
                Column(
                    modifier = Modifier.padding(start = 32.dp, end = 12.dp, bottom = 8.dp)
                ) {
                    group.sessions.forEach { session ->
                        SessionItem(
                            session = session,
                            onSessionClick = onSessionClick,
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
private fun SessionItem(
    session: ChatSession,
    onSessionClick: (ChatSession) -> Unit,
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
            // Левый блок со строками
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Первая строка: название слева, время справа
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = session.displayPreview(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = session.displayTime()
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Вторая строка: полный путь слева (только если не в группе), количество сообщений справа
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isGrouped) {
                        Text(
                            text = session.displayProject(),
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Text(
                        text = "${session.messageCount} сообщений"
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Правый блок с кнопкой
            CompactButton(
                onClick = { onSessionClick(session) }
            ) {
                Text("Продолжить")
            }
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