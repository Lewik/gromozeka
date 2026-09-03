package com.gromozeka.presentation.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteProjectMembershipService
import com.gromozeka.client.RemoteUserDirectoryService
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import com.gromozeka.remote.protocol.UserDirectoryEntry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
fun ConversationParticipantsPanel(
    isVisible: Boolean,
    initialConversation: Conversation,
    currentUserId: User.Id,
    conversationService: ConversationDomainService,
    agentService: AgentDomainService,
    projectMembershipService: RemoteProjectMembershipService,
    userDirectoryService: RemoteUserDirectoryService,
    onConversationUpdated: (Conversation) -> Unit,
    onCurrentUserDisconnected: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    slideFromRight: Boolean = false,
) {
    var conversation by remember(initialConversation.id) { mutableStateOf(initialConversation) }
    var memberships by remember(initialConversation.projectId) { mutableStateOf(emptyList<ProjectMembership>()) }
    var users by remember { mutableStateOf(emptyList<UserDirectoryEntry>()) }
    var agents by remember(initialConversation.projectId) { mutableStateOf(emptyList<AgentDefinition>()) }
    var loadedSources by remember(initialConversation.id) { mutableStateOf(emptySet<ParticipantSource>()) }
    var updating by remember(initialConversation.id) { mutableStateOf(false) }
    var error by remember(initialConversation.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(initialConversation) {
        conversation = initialConversation
    }

    LaunchedEffect(isVisible, initialConversation.id, initialConversation.projectId) {
        if (!isVisible) return@LaunchedEffect

        coroutineScope {
            launch {
                conversationService.observeByProject(initialConversation.projectId)
                    .catch { failure ->
                        error = failure.message ?: "Failed to load conversation"
                        loadedSources += ParticipantSource.CONVERSATION
                    }
                    .collect { conversations ->
                        val updated = conversations.firstOrNull { it.id == initialConversation.id }
                        if (updated == null) {
                            onCurrentUserDisconnected()
                        } else {
                            conversation = updated
                            onConversationUpdated(updated)
                        }
                        loadedSources += ParticipantSource.CONVERSATION
                    }
            }
            launch {
                projectMembershipService.observe(initialConversation.projectId)
                    .catch { failure ->
                        error = failure.message ?: "Failed to load project members"
                        loadedSources += ParticipantSource.MEMBERSHIPS
                    }
                    .collect {
                        memberships = it
                        loadedSources += ParticipantSource.MEMBERSHIPS
                    }
            }
            launch {
                userDirectoryService.observe()
                    .catch { failure ->
                        error = failure.message ?: "Failed to load users"
                        loadedSources += ParticipantSource.USERS
                    }
                    .collect {
                        users = it
                        loadedSources += ParticipantSource.USERS
                    }
            }
            launch {
                agentService.observeAll()
                    .catch { failure ->
                        error = failure.message ?: "Failed to load agents"
                        loadedSources += ParticipantSource.AGENTS
                    }
                    .collect {
                        agents = it.filter { agent ->
                            agent.projectId == null || agent.projectId == initialConversation.projectId
                        }
                        loadedSources += ParticipantSource.AGENTS
                    }
            }
        }
    }

    fun updateParticipants(participants: Set<Conversation.Participant>) {
        if (updating) return
        scope.launch {
            updating = true
            error = null
            runCatching {
                requireNotNull(
                    conversationService.updateParticipants(conversation.id, participants)
                ) { "Conversation not found: ${conversation.id.value}" }
            }.onSuccess { updated ->
                conversation = updated
                onConversationUpdated(updated)
                if (Conversation.Participant.User(currentUserId) !in updated.participants) {
                    onCurrentUserDisconnected()
                }
            }.onFailure { failure ->
                error = failure.message ?: "Failed to update participants"
            }
            updating = false
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = if (slideFromRight) slideInHorizontally(initialOffsetX = { it }) else expandHorizontally(),
        exit = if (slideFromRight) slideOutHorizontally(targetOffsetX = { it }) else shrinkHorizontally(),
        modifier = modifier,
    ) {
        Surface(
            modifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.width(420.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Participants",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close participants")
                    }
                }

                val allSourcesLoaded = loadedSources.size == ParticipantSource.entries.size
                if (!allSourcesLoaded) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val currentMembership = memberships.firstOrNull { it.userId == currentUserId }
                    val canManage = currentMembership?.role?.allows(ProjectPermission.WRITE) == true
                    val connectedUserIds = conversation.participants
                        .filterIsInstance<Conversation.Participant.User>()
                        .mapTo(mutableSetOf(), Conversation.Participant.User::userId)
                    val connectedAgentIds = conversation.participants
                        .filterIsInstance<Conversation.Participant.Agent>()
                        .mapTo(mutableSetOf(), Conversation.Participant.Agent::agentDefinitionId)

                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!canManage) {
                            Text(
                                text = "Project write permission is required to change participants.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        error?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        ParticipantSectionTitle("Users", connectedUserIds.size)
                        val usersById = users.associateBy(UserDirectoryEntry::id)
                        val eligibleUserIds = (memberships.map(ProjectMembership::userId) + connectedUserIds)
                            .distinct()
                            .sortedBy { usersById[it]?.displayName ?: it.value }
                        eligibleUserIds.forEach { userId ->
                            val user = usersById[userId]
                            val connected = userId in connectedUserIds
                            ParticipantRow(
                                title = user?.displayName ?: "Unavailable user",
                                subtitle = user?.username?.let { "@$it" } ?: userId.value,
                                testTagId = userId.value,
                                agent = false,
                                connected = connected,
                                enabled = canManage && !updating && !(connected && connectedUserIds.size == 1),
                                onConnectedChange = { shouldConnect ->
                                    val participant = Conversation.Participant.User(userId)
                                    updateParticipants(
                                        if (shouldConnect) {
                                            conversation.participants + participant
                                        } else {
                                            conversation.participants - participant
                                        }
                                    )
                                },
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider()
                        ParticipantSectionTitle("Agents", connectedAgentIds.size)
                        val agentsById = agents.associateBy(AgentDefinition::id)
                        val eligibleAgentIds = (agents.map(AgentDefinition::id) + connectedAgentIds)
                            .distinct()
                            .sortedBy { agentsById[it]?.name ?: it.value }
                        eligibleAgentIds.forEach { agentId ->
                            val agent = agentsById[agentId]
                            val connected = agentId in connectedAgentIds
                            ParticipantRow(
                                title = agent?.name ?: "Unavailable agent",
                                subtitle = when (agent?.type) {
                                    is AgentDefinition.Type.Global -> "Global agent"
                                    is AgentDefinition.Type.Project -> "Project agent"
                                    null -> agentId.value
                                },
                                testTagId = agentId.value,
                                agent = true,
                                connected = connected,
                                enabled = canManage && !updating,
                                onConnectedChange = { shouldConnect ->
                                    val participant = Conversation.Participant.Agent(agentId)
                                    updateParticipants(
                                        if (shouldConnect) {
                                            conversation.participants + participant
                                        } else {
                                            conversation.participants - participant
                                        }
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParticipantSectionTitle(title: String, connectedCount: Int) {
    Text(
        text = "$title · $connectedCount connected",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ParticipantRow(
    title: String,
    subtitle: String,
    testTagId: String,
    agent: Boolean,
    connected: Boolean,
    enabled: Boolean,
    onConnectedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (agent) Icons.Default.SmartToy else Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(
                checked = connected,
                onCheckedChange = onConnectedChange,
                enabled = enabled,
                modifier = Modifier.testTag(
                    UiTestTag.ParticipantToggle(if (agent) "agent" else "user", testTagId).value
                ),
            )
        }
    }
}

private enum class ParticipantSource {
    CONVERSATION,
    MEMBERSHIPS,
    USERS,
    AGENTS,
}
