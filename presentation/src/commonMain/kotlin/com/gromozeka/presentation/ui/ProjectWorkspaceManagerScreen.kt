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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.WorkspaceCatalogService
import com.gromozeka.domain.service.WorkspaceManagementService
import kotlinx.coroutines.launch

@Composable
fun ProjectManagerScreen(
    projectService: ProjectDomainService,
    onBack: () -> Unit,
    onManageWorkspaces: (Project.Id?) -> Unit,
    onChanged: () -> Unit,
) {
    val strings = managementStrings()
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var selectedProjectId by remember { mutableStateOf<Project.Id?>(null) }
    var editorProject by remember { mutableStateOf<Project?>(null) }
    var showCreateEditor by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<Project?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }

    suspend fun reload() {
        loading = true
        runCatching { projectService.findAll() }
            .onSuccess { loaded ->
                projects = loaded
                selectedProjectId = selectedProjectId
                    ?.takeIf { selected -> loaded.any { it.id == selected } }
                    ?: loaded.firstOrNull()?.id
                error = null
            }
            .onFailure { error = it.message ?: strings.operationFailed }
        loading = false
    }

    LaunchedEffect(reloadKey) { reload() }

    val selectedProject = projects.firstOrNull { it.id == selectedProjectId }
    ManagerScaffold(
        title = strings.projects,
        onBack = onBack,
        actions = {
            CompactButton(onClick = { onManageWorkspaces(selectedProjectId) }) {
                Text(strings.workspaces)
            }
            Spacer(Modifier.width(8.dp))
            CompactButton(onClick = { showCreateEditor = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(strings.newProject)
            }
        },
    ) {
        error?.let { ManagerError(it) }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            ManagerMasterDetail(
                master = {
                    if (projects.isEmpty()) {
                        EmptyManagerState(strings.noProjects)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(projects, key = { it.id.value }) { project ->
                                ManagerListItem(
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
                        EmptyManagerState(strings.selectProject)
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(selectedProject.name, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                selectedProject.description ?: strings.noDescription,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IdLine("Project ID", selectedProject.id.value)
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactButton(onClick = { editorProject = selectedProject }) {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(strings.edit)
                                }
                                CompactButton(onClick = { onManageWorkspaces(selectedProject.id) }) {
                                    Text(strings.manageWorkspaces)
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
                                    Text(strings.delete)
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
            strings = strings,
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
                        reloadKey++
                        onChanged()
                    }.onFailure { error = it.message ?: strings.operationFailed }
                }
            },
        )
    }

    projectToDelete?.let { project ->
        ConfirmDestructiveDialog(
            title = strings.deleteProject,
            body = strings.deleteProjectBody.replace("{name}", project.name),
            strings = strings,
            onDismiss = { projectToDelete = null },
            onConfirm = {
                scope.launch {
                    runCatching { projectService.delete(project.id) }
                        .onSuccess {
                            projectToDelete = null
                            reloadKey++
                            onChanged()
                        }
                        .onFailure { error = it.message ?: strings.operationFailed }
                }
            },
        )
    }
}

@Composable
fun WorkspaceManagerScreen(
    initialProjectId: Project.Id?,
    projectService: ProjectDomainService,
    workspaceCatalogService: WorkspaceCatalogService,
    workspaceManagementService: WorkspaceManagementService,
    onBack: () -> Unit,
    onManageProjects: () -> Unit,
) {
    val strings = managementStrings()
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var selectedProjectId by remember(initialProjectId) { mutableStateOf(initialProjectId) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var selectedWorkspaceId by remember { mutableStateOf<Workspace.Id?>(null) }
    var mounts by remember { mutableStateOf<List<WorkspaceMount>>(emptyList()) }
    var editorWorkspace by remember { mutableStateOf<Workspace?>(null) }
    var showCreateEditor by remember { mutableStateOf(false) }
    var workspaceToDelete by remember { mutableStateOf<Workspace?>(null) }
    var mountToDelete by remember { mutableStateOf<WorkspaceMount?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }

    suspend fun reload() {
        loading = true
        runCatching {
            val loadedProjects = projectService.findAll()
            val projectId = selectedProjectId
                ?.takeIf { selected -> loadedProjects.any { it.id == selected } }
                ?: loadedProjects.firstOrNull()?.id
            val loadedWorkspaces = projectId?.let { workspaceCatalogService.findByProject(it) }.orEmpty()
            val workspaceId = selectedWorkspaceId
                ?.takeIf { selected -> loadedWorkspaces.any { it.id == selected } }
                ?: loadedWorkspaces.firstOrNull()?.id
            val loadedMounts = workspaceId?.let { workspaceCatalogService.findMounts(it) }.orEmpty()
            WorkspaceManagerSnapshot(loadedProjects, projectId, loadedWorkspaces, workspaceId, loadedMounts)
        }.onSuccess { snapshot ->
            projects = snapshot.projects
            selectedProjectId = snapshot.projectId
            workspaces = snapshot.workspaces
            selectedWorkspaceId = snapshot.workspaceId
            mounts = snapshot.mounts
            error = null
        }.onFailure { error = it.message ?: strings.operationFailed }
        loading = false
    }

    LaunchedEffect(reloadKey, selectedProjectId) { reload() }

    val selectedWorkspace = workspaces.firstOrNull { it.id == selectedWorkspaceId }
    ManagerScaffold(
        title = strings.workspaces,
        onBack = onBack,
        actions = {
            CompactButton(onClick = onManageProjects) { Text(strings.projects) }
            Spacer(Modifier.width(8.dp))
            CompactButton(
                onClick = { showCreateEditor = true },
                enabled = selectedProjectId != null,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(strings.newWorkspace)
            }
        },
    ) {
        error?.let { ManagerError(it) }
        ProjectSelector(
            projects = projects,
            selectedProjectId = selectedProjectId,
            strings = strings,
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
                        EmptyManagerState(strings.noWorkspaces)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(workspaces, key = { it.id.value }) { workspace ->
                                ManagerListItem(
                                    title = workspace.name,
                                    subtitle = workspace.kind.name.lowercase(),
                                    selected = workspace.id == selectedWorkspaceId,
                                    onClick = {
                                        selectedWorkspaceId = workspace.id
                                        reloadKey++
                                    },
                                )
                            }
                        }
                    }
                },
                detail = {
                    if (selectedWorkspace == null) {
                        EmptyManagerState(strings.selectWorkspace)
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
                                    Text(strings.edit)
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
                                    Text(strings.delete)
                                }
                            }
                            IdLine("Workspace ID", selectedWorkspace.id.value)
                            HorizontalDivider()
                            Text(strings.mounts, style = MaterialTheme.typography.titleMedium)
                            Text(
                                strings.mountExplanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (mounts.isEmpty()) {
                                Text(strings.noMounts, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(mounts, key = { it.id.value }) { mount ->
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
                                                    Text(strings.detach)
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
            strings = strings,
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
                        reloadKey++
                    }.onFailure { error = it.message ?: strings.operationFailed }
                }
            },
        )
    }

    workspaceToDelete?.let { workspace ->
        ConfirmDestructiveDialog(
            title = strings.deleteWorkspace,
            body = strings.deleteWorkspaceBody.replace("{name}", workspace.name),
            strings = strings,
            onDismiss = { workspaceToDelete = null },
            onConfirm = {
                scope.launch {
                    runCatching { workspaceManagementService.delete(workspace.id) }
                        .onSuccess {
                            workspaceToDelete = null
                            reloadKey++
                        }
                        .onFailure { error = it.message ?: strings.operationFailed }
                }
            },
        )
    }

    mountToDelete?.let { mount ->
        ConfirmDestructiveDialog(
            title = strings.detachMount,
            body = strings.detachMountBody.replace("{path}", mount.rootPath),
            strings = strings,
            onDismiss = { mountToDelete = null },
            onConfirm = {
                scope.launch {
                    runCatching { workspaceManagementService.deleteMount(mount.id) }
                        .onSuccess {
                            mountToDelete = null
                            reloadKey++
                        }
                        .onFailure { error = it.message ?: strings.operationFailed }
                }
            },
        )
    }
}

@Composable
private fun ManagerScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
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
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
    strings: ManagementStrings,
    onSelect: (Project.Id) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.project, style = MaterialTheme.typography.labelLarge)
        if (projects.isEmpty()) {
            Text(strings.noProjects, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    strings: ManagementStrings,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit,
) {
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var description by remember(project?.id) { mutableStateOf(project?.description.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (project == null) strings.newProject else strings.editProject) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(strings.name) }, singleLine = true)
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text(strings.description) },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description.takeIf(String::isNotBlank)) }, enabled = name.isNotBlank()) {
                Text(strings.save)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun WorkspaceEditorDialog(
    workspace: Workspace?,
    strings: ManagementStrings,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(workspace?.id) { mutableStateOf(workspace?.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (workspace == null) strings.newWorkspace else strings.editWorkspace) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(strings.name) }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun ConfirmDestructiveDialog(
    title: String,
    body: String,
    strings: ManagementStrings,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(strings.delete) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
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

@Composable
private fun managementStrings(): ManagementStrings {
    val languageCode = LocalTranslation.current.languageCode
    return remember(languageCode) {
        if (languageCode == "ru") ManagementStrings.russian else ManagementStrings.english
    }
}

private data class WorkspaceManagerSnapshot(
    val projects: List<Project>,
    val projectId: Project.Id?,
    val workspaces: List<Workspace>,
    val workspaceId: Workspace.Id?,
    val mounts: List<WorkspaceMount>,
)

private data class ManagementStrings(
    val projects: String,
    val project: String,
    val workspaces: String,
    val newProject: String,
    val newWorkspace: String,
    val editProject: String,
    val editWorkspace: String,
    val edit: String,
    val delete: String,
    val detach: String,
    val save: String,
    val cancel: String,
    val name: String,
    val description: String,
    val noDescription: String,
    val noProjects: String,
    val noWorkspaces: String,
    val noMounts: String,
    val selectProject: String,
    val selectWorkspace: String,
    val manageWorkspaces: String,
    val mounts: String,
    val mountExplanation: String,
    val deleteProject: String,
    val deleteProjectBody: String,
    val deleteWorkspace: String,
    val deleteWorkspaceBody: String,
    val detachMount: String,
    val detachMountBody: String,
    val operationFailed: String,
) {
    companion object {
        val english = ManagementStrings(
            projects = "Projects",
            project = "Project",
            workspaces = "Workspaces",
            newProject = "New project",
            newWorkspace = "New workspace",
            editProject = "Edit project",
            editWorkspace = "Edit workspace",
            edit = "Edit",
            delete = "Delete",
            detach = "Detach",
            save = "Save",
            cancel = "Cancel",
            name = "Name",
            description = "Description",
            noDescription = "No description",
            noProjects = "No projects yet",
            noWorkspaces = "This project has no workspaces",
            noMounts = "This workspace has no mounts",
            selectProject = "Select a project",
            selectWorkspace = "Select a workspace",
            manageWorkspaces = "Manage workspaces",
            mounts = "Workspace mounts",
            mountExplanation = "A mount is the exact worker-local filesystem location used for tool execution.",
            deleteProject = "Delete project?",
            deleteProjectBody = "Project {name}, its conversations, workspaces, and mounts will be deleted permanently.",
            deleteWorkspace = "Delete workspace?",
            deleteWorkspaceBody = "Workspace {name} and all of its mounts will be deleted permanently.",
            detachMount = "Detach mount?",
            detachMountBody = "The worker path {path} will no longer be available through this workspace.",
            operationFailed = "Operation failed",
        )

        val russian = ManagementStrings(
            projects = "Проекты",
            project = "Проект",
            workspaces = "Рабочие пространства",
            newProject = "Новый проект",
            newWorkspace = "Новое пространство",
            editProject = "Изменить проект",
            editWorkspace = "Изменить пространство",
            edit = "Изменить",
            delete = "Удалить",
            detach = "Отключить",
            save = "Сохранить",
            cancel = "Отмена",
            name = "Название",
            description = "Описание",
            noDescription = "Нет описания",
            noProjects = "Проектов пока нет",
            noWorkspaces = "В проекте нет рабочих пространств",
            noMounts = "У рабочего пространства нет mount-ов",
            selectProject = "Выберите проект",
            selectWorkspace = "Выберите рабочее пространство",
            manageWorkspaces = "Управлять пространствами",
            mounts = "Workspace mounts",
            mountExplanation = "Mount — точное расположение файловой системы на конкретном worker для выполнения tools.",
            deleteProject = "Удалить проект?",
            deleteProjectBody = "Проект {name}, его conversations, workspaces и mounts будут удалены безвозвратно.",
            deleteWorkspace = "Удалить workspace?",
            deleteWorkspaceBody = "Workspace {name} и все его mounts будут удалены безвозвратно.",
            detachMount = "Отключить mount?",
            detachMountBody = "Путь worker {path} больше не будет доступен через этот workspace.",
            operationFailed = "Операция не выполнена",
        )
    }
}
