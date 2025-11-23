package com.gromozeka.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.repository.AgentDomainService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import klog.KLoggers

private val log = KLoggers.logger("TabSettingsPanel")

@Composable
fun TabSettingsPanel(
    projectPath: String,
    isVisible: Boolean,
    currentAgent: Agent,
    onAgentChange: (Agent) -> Unit,
    onClose: () -> Unit,
    agentService: AgentDomainService,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    var agents by remember { mutableStateOf<List<Agent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            isLoading = true
            error = null
            try {
                val loadedAgents = agentService.findAll(projectPath)
                
                agents = loadedAgents.sortedWith(
                    compareBy<Agent> { agent ->
                        when (val type = agent.type) {
                            is Agent.Type.Project -> 0
                            is Agent.Type.Global -> 1
                            is Agent.Type.Builtin -> 2
                            is Agent.Type.Inline -> 3
                        }
                    }.thenBy { it.name }
                )
                
                log.info { "Loaded ${agents.size} agents (PROJECT first)" }
            } catch (e: Exception) {
                error = "Failed to load agents: ${e.message}"
                log.error(e) { "Error loading agents" }
            } finally {
                isLoading = false
            }
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = expandHorizontally(),
        exit = shrinkHorizontally(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .width(400.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Tab Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Select Agent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

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
                            Text(
                                text = error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    else -> {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            agents.forEach { agent ->
                                AgentRadioItem(
                                    agent = agent,
                                    isSelected = agent.id == currentAgent.id,
                                    onClick = {
                                        coroutineScope.launch {
                                            onAgentChange(agent)
                                            log.info { "Changed agent to: ${agent.name}" }
                                        }
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
private fun AgentRadioItem(
    agent: Agent,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null
            )

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = when (val type = agent.type) {
                    is Agent.Type.Project -> Icons.Default.Folder
                    is Agent.Type.Global -> Icons.Default.Home
                    is Agent.Type.Builtin -> Icons.Default.Lock
                    is Agent.Type.Inline -> Icons.Default.Description
                },
                contentDescription = when (val type = agent.type) {
                    is Agent.Type.Project -> "Project"
                    is Agent.Type.Global -> "Global"
                    is Agent.Type.Builtin -> "Builtin"
                    is Agent.Type.Inline -> "Inline"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )

                agent.description?.let { desc ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${agent.prompts.size} prompts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
