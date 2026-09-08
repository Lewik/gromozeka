package com.gromozeka.presentation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.client.RemoteProjectMembershipService
import com.gromozeka.client.RemoteUserDirectoryService
import com.gromozeka.remote.protocol.UserDirectoryEntry
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.WorkspaceCatalogService
import com.gromozeka.domain.service.WorkspaceManagementService
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.WorkerCatalogService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Composable
fun ProjectManagerScreen(
    projectService: ProjectDomainService,
    projectMembershipService: RemoteProjectMembershipService,
    userDirectoryService: RemoteUserDirectoryService,
    onBack: () -> Unit,
    onManageWorkspaces: (Project.Id?) -> Unit,
    onChanged: () -> Unit,
) {
    val translation = LocalTranslation.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var selectedProjectId by remember { mutableStateOf<Project.Id?>(null) }
    var editorProject by remember { mutableStateOf<Project?>(null) }
    var showCreateEditor by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<Project?>(null) }
    var membersProject by remember { mutableStateOf<Project?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(projectService) {
        loading = true
        projectService.observeAll()
            .catch { failure ->
                error = failure
                loading = false
            }
            .collect { loaded ->
                projects = loaded
                selectedProjectId = selectedProjectId
                    ?.takeIf { selected -> loaded.any { it.id == selected } }
                    ?: loaded.firstOrNull()?.id
                error = null
                loading = false
            }
    }

    val selectedProject = projects.firstOrNull { it.id == selectedProjectId }
    ManagerScaffold(
        modifier = Modifier.testTag(UiTestTag.ProjectManager.value),
        title = translation.text("projects.title"),
        onBack = onBack,
        actions = {
            CompactButton(onClick = { onManageWorkspaces(selectedProjectId) }) {
                Text(translation.text("projects.workspaces.title"))
            }
            Spacer(Modifier.width(8.dp))
            CompactButton(
                onClick = { showCreateEditor = true },
                modifier = Modifier.testTag(UiTestTag.NewProjectButton.value),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(translation.text("projects.create.title"))
            }
        },
    ) {
        error?.let { ManagerError(it.message ?: translation.text("projects.operationFailed")) }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            ManagerMasterDetail(
                master = {
                    if (projects.isEmpty()) {
                        EmptyManagerState(translation.text("projects.empty"))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(projects, key = { it.id.value }) { project ->
                                ManagerListItem(
                                    modifier = Modifier.testTag(UiTestTag.ProjectItem(project.id.value).value),
                                    title = project.name,
                                    subtitle = project.description,
                                    selected = project.id == selectedProjectId,
                                    onClick = { selectedProjectId = project.id },
                                )
                            }
                        }
                    }
                },
                detail = {
                    if (selectedProject == null) {
                        EmptyManagerState(translation.text("projects.selectProject"))
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(selectedProject.name, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                selectedProject.description ?: translation.text("projects.description.empty"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IdLine(translation.text("projects.projectIdLabel"), selectedProject.id.value)
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactButton(onClick = { editorProject = selectedProject }) {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(translation.text("projects.action.edit"))
                                }
                                CompactButton(onClick = { onManageWorkspaces(selectedProject.id) }) {
                                    Text(translation.text("projects.workspaces.manage"))
                                }
                                CompactButton(onClick = { membersProject = selectedProject }) {
                                    Text(translation.text("projects.members.manage"))
                                }
                                CompactButton(
                                    onClick = { projectToDelete = selectedProject },
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(translation.text("projects.action.delete"))
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    if (showCreateEditor || editorProject != null) {
        ProjectEditorDialog(
            project = editorProject,
            onDismiss = {
                showCreateEditor = false
                editorProject = null
            },
            onSave = { name, description ->
                scope.launch {
                    runCatching {
                        editorProject?.let { projectService.update(it.id, name, description) }
                            ?: projectService.create(name, description)
                    }.onSuccess { project ->
                        selectedProjectId = project.id
                        showCreateEditor = false
                        editorProject = null
                        onChanged()
                    }.onFailure { error = it }
                }
            },
        )
    }

    projectToDelete?.let { project ->
        ConfirmDestructiveDialog(
            title = translation.text("projects.delete.title"),
            body = translation.text("projects.delete.description", "name" to project.name),
            onDismiss = { projectToDelete = null },
            onConfirm = {
                scope.launch {
                    runCatching { projectService.delete(project.id) }
                        .onSuccess {
                            projectToDelete = null
                            onChanged()
                        }
                        .onFailure { error = it }
                }
            },
        )
    }

    membersProject?.let { project ->
        ProjectMembersDialog(
            project = project,
            projectMembershipService = projectMembershipService,
            userDirectoryService = userDirectoryService,
            onDismiss = { membersProject = null },
        )
    }
}

@Composable
@OptIn(ExperimentalCoroutinesApi::class)
fun WorkspaceManagerScreen(
    initialProjectId: Project.Id?,
    projectService: ProjectDomainService,
    workspaceCatalogService: WorkspaceCatalogService,
    workspaceManagementService: WorkspaceManagementService,
    workerCatalogService: WorkerCatalogService,
    onBack: () -> Unit,
    onManageProjects: () -> Unit,
) {
    val translation = LocalTranslation.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var selectedProjectId by remember(initialProjectId) { mutableStateOf(initialProjectId) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var selectedWorkspaceId by remember { mutableStateOf<Workspace.Id?>(null) }
    var mounts by remember { mutableStateOf<List<WorkspaceMount>>(emptyList()) }
    var workers by remember { mutableStateOf<List<WorkerCatalogEntry>>(emptyList()) }
    var editorWorkspace by remember { mutableStateOf<Workspace?>(null) }
    var showCreateEditor by remember { mutableStateOf(false) }
    var workspaceToDelete by remember { mutableStateOf<Workspace?>(null) }
    var mountToDelete by remember { mutableStateOf<WorkspaceMount?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(selectedProjectId, selectedWorkspaceId) {
        loading = true
        projectService.observeAll().flatMapLatest { loadedProjects ->
            val projectId = selectedProjectId
                ?.takeIf { selected -> loadedProjects.any { it.id == selected } }
                ?: loadedProjects.firstOrNull()?.id
            val workspacesFlow = projectId
                ?.let(workspaceCatalogService::observeByProject)
                ?: flowOf(emptyList())
            combine(workspacesFlow, workerCatalogService.observeWorkers()) { loadedWorkspaces, loadedWorkers ->
                Triple(loadedProjects, projectId, loadedWorkspaces to loadedWorkers)
            }.flatMapLatest { (projectsSnapshot, projectIdSnapshot, workspaceAndWorkers) ->
                val (loadedWorkspaces, loadedWorkers) = workspaceAndWorkers
                val workspaceId = selectedWorkspaceId
                    ?.takeIf { selected -> loadedWorkspaces.any { it.id == selected } }
                    ?: loadedWorkspaces.firstOrNull()?.id
                val mountsFlow = workspaceId
                    ?.let(workspaceCatalogService::observeMounts)
                    ?: flowOf(emptyList())
                mountsFlow.map { loadedMounts ->
                    WorkspaceManagerSnapshot(
                        projectsSnapshot,
                        projectIdSnapshot,
                        loadedWorkspaces,
                        workspaceId,
                        loadedMounts,
                        loadedWorkers,
                    )
                }
            }
        }.catch { failure ->
            error = failure
            loading = false
        }.collect { snapshot ->
            projects = snapshot.projects
            selectedProjectId = snapshot.projectId
            workspaces = snapshot.workspaces
            selectedWorkspaceId = snapshot.workspaceId
            mounts = snapshot.mounts
            workers = snapshot.workers
            error = null
            loading = false
        }
    }

    val selectedWorkspace = workspaces.firstOrNull { it.id == selectedWorkspaceId }
    ManagerScaffold(
        title = translation.text("projects.workspaces.title"),
        onBack = onBack,
        actions = {
            CompactButton(onClick = onManageProjects) { Text(translation.text("projects.title")) }
            Spacer(Modifier.width(8.dp))
            CompactButton(
                onClick = { showCreateEditor = true },
                enabled = selectedProjectId != null,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(translation.text("projects.workspaces.create.title"))
            }
        },
    ) {
        error?.let { ManagerError(it.message ?: translation.text("projects.operationFailed")) }
        ProjectSelector(
            projects = projects,
            selectedProjectId = selectedProjectId,
            onSelect = {
                selectedProjectId = it
                selectedWorkspaceId = null
            },
        )
        Spacer(Modifier.height(10.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            ManagerMasterDetail(
                master = {
                    if (workspaces.isEmpty()) {
                        EmptyManagerState(translation.text("projects.workspaces.empty"))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(workspaces, key = { it.id.value }) { workspace ->
                                ManagerListItem(
                                    title = workspace.name,
                                    subtitle = workspace.kind.displayName(translation),
                                    selected = workspace.id == selectedWorkspaceId,
                                    onClick = {
                                        selectedWorkspaceId = workspace.id
                                    },
                                )
                            }
                        }
                    }
                },
                detail = {
                    if (selectedWorkspace == null) {
                        EmptyManagerState(translation.text("projects.workspaces.select"))
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    selectedWorkspace.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                CompactButton(onClick = { editorWorkspace = selectedWorkspace }) {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(translation.text("projects.action.edit"))
                                }
                                Spacer(Modifier.width(8.dp))
                                CompactButton(
                                    onClick = { workspaceToDelete = selectedWorkspace },
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(translation.text("projects.action.delete"))
                                }
                            }
                            IdLine(translation.text("projects.workspaces.idLabel"), selectedWorkspace.id.value)
                            HorizontalDivider()
                            Text(translation.text("projects.mounts.title"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                translation.text("projects.mounts.description"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (mounts.isEmpty()) {
                                Text(translation.text("projects.mounts.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(mounts, key = { it.id.value }) { mount ->
                                        val worker = workers.firstOrNull { it.workerId.value == mount.workerId }
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = MaterialTheme.shapes.small,
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(mount.workerId, fontWeight = FontWeight.SemiBold)
                                                    Text(
                                                        worker?.environmentSummary(translation)
                                                            ?: translation.text("projects.workers.profileUnavailable"),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Text(
                                                        mount.rootPath,
                                                        fontFamily = FontFamily.Monospace,
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                    Text(
                                                        mount.id.value,
                                                        fontFamily = FontFamily.Monospace,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                TextButton(onClick = { mountToDelete = mount }) {
                                                    Text(translation.text("projects.mounts.action.detach"))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    if (showCreateEditor || editorWorkspace != null) {
        WorkspaceEditorDialog(
            workspace = editorWorkspace,
            onDismiss = {
                showCreateEditor = false
                editorWorkspace = null
            },
            onSave = { name ->
                scope.launch {
                    runCatching {
                        editorWorkspace?.let { workspaceManagementService.update(it.id, name) }
                            ?: workspaceManagementService.create(
                                projectId = requireNotNull(selectedProjectId),
                                name = name,
                            )
                    }.onSuccess { workspace ->
                        selectedWorkspaceId = workspace.id
                        showCreateEditor = false
                        editorWorkspace = null
                    }.onFailure { error = it }
                }
            },
        )
    }

    workspaceToDelete?.let { workspace ->
        ConfirmDestructiveDialog(
            title = translation.text("projects.workspaces.delete.title"),
            body = translation.text("projects.workspaces.delete.description", "name" to workspace.name),
            onDismiss = { workspaceToDelete = null },
            onConfirm = {
                scope.launch {
                    runCatching { workspaceManagementService.delete(workspace.id) }
                        .onSuccess {
                            workspaceToDelete = null
                        }
                        .onFailure { error = it }
                }
            },
        )
    }

    mountToDelete?.let { mount ->
        ConfirmDestructiveDialog(
            title = translation.text("projects.mounts.detach.title"),
            body = translation.text("projects.mounts.detach.description", "path" to mount.rootPath),
            onDismiss = { mountToDelete = null },
            onConfirm = {
                scope.launch {
                    runCatching { workspaceManagementService.deleteMount(mount.id) }
                        .onSuccess {
                            mountToDelete = null
                        }
                        .onFailure { error = it }
                }
            },
        )
    }
}

@Composable
private fun ManagerScaffold(
    modifier: Modifier = Modifier,
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                actions()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ManagerMasterDetail(
    master: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < 720.dp) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(0.42f)) { master() }
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().weight(0.58f)) { detail() }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(300.dp).fillMaxHeight().padding(end = 12.dp)) { master() }
                VerticalDivider(Modifier.fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
            }
        }
    }
}

@Composable
private fun ManagerListItem(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProjectSelector(
    projects: List<Project>,
    selectedProjectId: Project.Id?,
    onSelect: (Project.Id) -> Unit,
) {
    val translation = LocalTranslation.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(translation.text("projects.projectLabel"), style = MaterialTheme.typography.labelLarge)
        if (projects.isEmpty()) {
            Text(translation.text("projects.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(projects, key = { it.id.value }) { project ->
                    CompactButton(
                        onClick = { onSelect(project.id) },
                        colors = if (project.id == selectedProjectId) {
                            androidx.compose.material3.ButtonDefaults.buttonColors()
                        } else {
                            androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                        },
                    ) {
                        Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectEditorDialog(
    project: Project?,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit,
) {
    val translation = LocalTranslation.current
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var description by remember(project?.id) { mutableStateOf(project?.description.orEmpty()) }
    AlertDialog(
        modifier = Modifier.testTag(UiTestTag.ProjectEditorDialog.value),
        onDismissRequest = onDismiss,
        title = { Text(if (project == null) translation.text("projects.create.title") else translation.text("projects.edit.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    modifier = Modifier.testTag(UiTestTag.ProjectNameInput.value),
                    label = { Text(translation.text("projects.field.name")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    description,
                    { description = it },
                    modifier = Modifier.testTag(UiTestTag.ProjectDescriptionInput.value),
                    label = { Text(translation.text("projects.field.description")) },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, description.takeIf(String::isNotBlank)) },
                modifier = Modifier.testTag(UiTestTag.ProjectSaveButton.value),
                enabled = name.isNotBlank(),
            ) {
                Text(translation.text("projects.action.save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(translation.text("projects.action.cancel")) } },
    )
}

@Composable
private fun WorkspaceEditorDialog(
    workspace: Workspace?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val translation = LocalTranslation.current
    var name by remember(workspace?.id) { mutableStateOf(workspace?.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (workspace == null) translation.text("projects.workspaces.create.title") else translation.text("projects.workspaces.edit.title")) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(translation.text("projects.field.name")) }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(translation.text("projects.action.save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(translation.text("projects.action.cancel")) } },
    )
}

@Composable
private fun ConfirmDestructiveDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val translation = LocalTranslation.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(translation.text("projects.action.delete")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(translation.text("projects.action.cancel")) } },
    )
}

@Composable
private fun ProjectMembersDialog(
    project: Project,
    projectMembershipService: RemoteProjectMembershipService,
    userDirectoryService: RemoteUserDirectoryService,
    onDismiss: () -> Unit,
) {
    val translation = LocalTranslation.current
    val scope = rememberCoroutineScope()
    var memberships by remember(project.id) { mutableStateOf<List<ProjectMembership>>(emptyList()) }
    var users by remember(project.id) { mutableStateOf<List<UserDirectoryEntry>>(emptyList()) }
    var selectedUserId by remember(project.id) { mutableStateOf<User.Id?>(null) }
    var selectedRole by remember(project.id) { mutableStateOf(ProjectMembership.Role.EDITOR) }
    var loading by remember(project.id) { mutableStateOf(true) }
    var error by remember(project.id) { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(project.id) {
        loading = true
        combine(
            projectMembershipService.observe(project.id),
            userDirectoryService.observe(),
        ) { loadedMemberships, loadedUsers -> loadedMemberships to loadedUsers }
            .catch { failure ->
                error = failure
                loading = false
            }
            .collect { (loadedMemberships, loadedUsers) ->
                memberships = loadedMemberships
                users = loadedUsers
                selectedUserId = selectedUserId?.takeIf { selected ->
                    loadedUsers.any { it.id == selected } &&
                        loadedMemberships.none { it.userId == selected }
                }
                error = null
                loading = false
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(translation.text("projects.members.titleWithProject", "projectName" to project.name)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { ManagerError(it.message ?: translation.text("projects.operationFailed")) }
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    val usersById = users.associateBy(UserDirectoryEntry::id)
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(memberships, key = { it.userId.value }) { membership ->
                            val directoryUser = usersById[membership.userId]
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column {
                                            Text(
                                                directoryUser?.displayName ?: membership.userId.value,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            directoryUser?.let {
                                                Text(
                                                    "@${it.username}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        TextButton(
                                            enabled = !loading,
                                            onClick = {
                                                scope.launch {
                                                    loading = true
                                                    runCatching {
                                                        projectMembershipService.remove(
                                                            project.id,
                                                            membership.userId,
                                                        )
                                                    }.onSuccess { loading = false }
                                                        .onFailure {
                                                            error = it
                                                            loading = false
                                                        }
                                                }
                                            },
                                        ) {
                                            Text(translation.text("projects.members.action.remove"))
                                        }
                                    }
                                    ProjectRoleSelector(
                                        role = membership.role,
                                        enabled = !loading,
                                        onRoleChange = { role ->
                                            scope.launch {
                                                loading = true
                                                    runCatching {
                                                        projectMembershipService.set(
                                                            project.id,
                                                            membership.userId,
                                                            role,
                                                        )
                                                    }.onSuccess { loading = false }
                                                    .onFailure {
                                                        error = it
                                                        loading = false
                                                    }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }

                    val availableUsers = users.filter { user ->
                        memberships.none { it.userId == user.id }
                    }
                    if (availableUsers.isNotEmpty()) {
                        HorizontalDivider()
                        Text(translation.text("projects.members.addTitle"), style = MaterialTheme.typography.titleSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(availableUsers, key = { it.id.value }) { user ->
                                FilterChip(
                                    selected = selectedUserId == user.id,
                                    onClick = { selectedUserId = user.id },
                                    label = { Text(user.displayName) },
                                )
                            }
                        }
                        ProjectRoleSelector(
                            role = selectedRole,
                            enabled = !loading,
                            onRoleChange = { selectedRole = it },
                        )
                        CompactButton(
                            enabled = selectedUserId != null && !loading,
                            onClick = {
                                val userId = requireNotNull(selectedUserId)
                                scope.launch {
                                    loading = true
                                    runCatching {
                                        projectMembershipService.set(
                                            project.id,
                                            userId,
                                            selectedRole,
                                        )
                                    }.onSuccess {
                                        selectedUserId = null
                                        loading = false
                                    }.onFailure {
                                        error = it
                                        loading = false
                                    }
                                }
                            },
                        ) {
                            Text(translation.text("projects.members.action.add"))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(translation.text("projects.members.action.close"))
            }
        },
    )
}

@Composable
private fun ProjectRoleSelector(
    role: ProjectMembership.Role,
    enabled: Boolean,
    onRoleChange: (ProjectMembership.Role) -> Unit,
) {
    val translation = LocalTranslation.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(ProjectMembership.Role.entries) { candidate ->
            FilterChip(
                selected = role == candidate,
                enabled = enabled,
                onClick = { onRoleChange(candidate) },
                label = {
                    Text(
                        when (candidate) {
                            ProjectMembership.Role.OWNER -> translation.text("projects.members.role.owner")
                            ProjectMembership.Role.EDITOR -> translation.text("projects.members.role.editor")
                            ProjectMembership.Role.VIEWER -> translation.text("projects.members.role.viewer")
                        }
                    )
                },
            )
        }
    }
}

@Composable
private fun IdLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun EmptyManagerState(text: String) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ManagerError(error: String) {
    Text(
        error,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.error,
    )
}

private data class WorkspaceManagerSnapshot(
    val projects: List<Project>,
    val projectId: Project.Id?,
    val workspaces: List<Workspace>,
    val workspaceId: Workspace.Id?,
    val mounts: List<WorkspaceMount>,
    val workers: List<WorkerCatalogEntry>,
)

private fun WorkerCatalogEntry.environmentSummary(translation: Translation): String {
    val statusLabel = when (status) {
        WorkerCatalogEntry.Status.ONLINE -> translation.text("projects.workers.status.online")
        WorkerCatalogEntry.Status.OFFLINE -> translation.text("projects.workers.status.offline")
    }
    return translation.text(
        "projects.workers.environmentSummary",
        "status" to statusLabel,
        "operatingSystem" to environmentProfile.operatingSystem.name,
        "operatingSystemVersion" to environmentProfile.operatingSystem.version,
        "architecture" to environmentProfile.architecture,
        "shell" to environmentProfile.nativeShell.executable,
    )
}

private fun Workspace.Kind.displayName(translation: Translation): String =
    when (this) {
        Workspace.Kind.FILESYSTEM -> translation.text("projects.workspaces.kind.filesystem")
    }
