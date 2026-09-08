package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteTelegramService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.CurrentUserNamedSecretService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun TelegramSettingsPanel(service: RemoteTelegramService, secrets: CurrentUserNamedSecretService, scope: CoroutineScope) {
    val tr = LocalTranslation.current
    var snapshot by remember { mutableStateOf<TelegramManagementSnapshot?>(null) }
    var secretNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var secretName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TelegramConnection?>(null) }
    var profile by remember { mutableStateOf<Pair<TelegramConnection, TelegramBotProfile>?>(null) }
    suspend fun reload() {
        snapshot = service.snapshot()
        secretNames = secrets.list().map { it.name }
    }
    fun perform(action: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try { action(); reload() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(service) {
        try { reload() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr.text("telegram.title"), style = MaterialTheme.typography.titleLarge)
        Text(tr.text("telegram.description"))
        if (snapshot?.serverEnabled == false) Text(tr.text("telegram.serverDisabled"), color = MaterialTheme.colorScheme.error)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy || snapshot == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        TelegramChoice(tr.text("telegram.tokenSecret"), secretName, secretNames.map { it to it }) { secretName = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && secretName.isNotBlank() && snapshot?.serverEnabled == true, onClick = {
                perform {
                    val bot = service.probe(secretName)
                    editing = snapshot?.connections?.firstOrNull { it.botId == bot.botId }
                        ?: TelegramConnection(bot.botId, bot.username, service.userId, secretName)
                }
            }) { Text(tr.text("telegram.connect")) }
            TextButton(enabled = !busy, onClick = { perform { } }) { Text(tr.text("telegram.reload")) }
        }
        snapshot?.connections.orEmpty().forEach { connection ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("@${connection.botUsername}", style = MaterialTheme.typography.titleMedium)
                    Text(tr.text(if (connection.enabled) "telegram.enabled" else "telegram.disabled"))
                    connection.bindings.forEach { binding ->
                        val target = snapshot?.targets?.firstOrNull { it.conversationId == binding.conversationId }
                        Text("${target?.projectName.orEmpty()} / ${target?.conversationName.orEmpty()} · ${binding.key}")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { editing = connection }) { Text(tr.text("telegram.configure")) }
                        TextButton(enabled = !busy && snapshot?.serverEnabled == true, onClick = {
                            perform { profile = connection to service.profile(connection.id) }
                        }) { Text(tr.text("telegram.profile")) }
                    }
                }
            }
        }
    }
    editing?.let { connection ->
        TelegramConnectionEditor(connection, snapshot?.targets.orEmpty(), snapshot?.serverEnabled == true, busy, error,
            onDismiss = { if (!busy) editing = null }, onSave = { edited -> perform { service.save(edited); editing = null } })
    }
    profile?.let { (connection, current) ->
        TelegramProfileEditor(current, busy, error, onDismiss = { if (!busy) profile = null }, onSave = { update ->
            perform { service.updateProfile(connection.id, update); profile = null }
        })
    }
}

private data class TelegramBindingDraft(
    val conversationId: Conversation.Id,
    val chatId: String = "",
    val topicId: String = "",
    val initiator: String = "",
    val enabled: Boolean = true,
    val locale: String = "en",
    val routes: List<TelegramRouteDraft> = emptyList(),
) {
    fun value() = TelegramConversationBinding(chatId.toLong(), topicId.takeIf { it.isNotBlank() }?.toLong(),
        conversationId, initiator.toLong(), routes.map { it.value() }, enabled, locale)
}

private data class TelegramRouteDraft(val agentId: AgentDefinition.Id, val trigger: String = "@grz",
    val contextPercent: Int = 50, val writeAllowed: Boolean = false, val instruction: String = "") {
    fun value() = TelegramAgentRoute(agentId, trigger, contextPercent, writeAllowed, instruction)
}

@Composable
private fun TelegramConnectionEditor(connection: TelegramConnection, targets: List<TelegramConversationTarget>,
    serverEnabled: Boolean, busy: Boolean, serverError: String?, onDismiss: () -> Unit, onSave: (TelegramConnection) -> Unit) {
    val tr = LocalTranslation.current
    var enabled by remember(connection) { mutableStateOf(connection.enabled) }
    var secret by remember(connection) { mutableStateOf(connection.tokenSecretName) }
    var bindings by remember(connection) { mutableStateOf(connection.bindings.map { binding ->
        TelegramBindingDraft(binding.conversationId, binding.chatId.toString(), binding.topicId?.toString().orEmpty(),
            binding.initiatorTelegramUserId.toString(), binding.enabled, binding.locale,
            binding.routes.map { TelegramRouteDraft(it.agentId, it.trigger, it.contextPercent, it.writeAllowed, it.additionalInstruction) })
    }) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("@${connection.botUsername}") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TelegramToggle(tr.text("telegram.enabled"), enabled, serverEnabled || enabled) { enabled = it }
            OutlinedTextField(secret, { secret = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr.text("telegram.tokenSecret")) }, singleLine = true)
            bindings.forEachIndexed { index, binding ->
                val options = targets.filter { target ->
                    target.externalChannel?.let { it.connectionId != connection.id } != true &&
                        bindings.withIndex().none { (i, other) -> i != index && other.conversationId == target.conversationId }
                }
                TelegramBindingEditor(binding, options, onChange = { updated -> bindings = bindings.toMutableList().apply { this[index] = updated } },
                    onRemove = { bindings = bindings.filterIndexed { i, _ -> i != index } })
            }
            val candidate = targets.firstOrNull { target -> target.agents.isNotEmpty() &&
                (target.externalChannel?.let { it.connectionId != connection.id } != true) &&
                bindings.none { it.conversationId == target.conversationId } }
            TextButton(enabled = candidate != null, onClick = {
                candidate?.let { bindings = bindings + TelegramBindingDraft(it.conversationId,
                    routes = listOf(TelegramRouteDraft(it.agents.first().agentId))) }
            }) { Text(tr.text("telegram.addGroup")) }
            Text(tr.text("telegram.inputPolicy"), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        Button(enabled = !busy, onClick = {
            try { onSave(connection.copy(enabled = enabled, tokenSecretName = secret, bindings = bindings.map { it.value() })) }
            catch (failure: IllegalArgumentException) { error = failure.message ?: tr.text("telegram.invalidSettings") }
        }) { Text(tr.text("security.users.save")) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr.text("agents.cancel")) } })
}

@Composable
private fun TelegramBindingEditor(binding: TelegramBindingDraft, targets: List<TelegramConversationTarget>,
    onChange: (TelegramBindingDraft) -> Unit, onRemove: () -> Unit) {
    val tr = LocalTranslation.current
    val target = targets.firstOrNull { it.conversationId == binding.conversationId }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TelegramChoice(tr.text("telegram.conversation"), binding.conversationId.value,
                targets.map { it.conversationId.value to "${it.projectName} / ${it.conversationName.ifBlank { it.conversationId.value }}" }) { id ->
                val selected = targets.first { it.conversationId.value == id }
                onChange(binding.copy(conversationId = selected.conversationId,
                    routes = selected.agents.firstOrNull()?.let { listOf(TelegramRouteDraft(it.agentId)) }.orEmpty()))
            }
            OutlinedTextField(binding.chatId, { onChange(binding.copy(chatId = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(tr.text("telegram.chatId")) }, singleLine = true)
            OutlinedTextField(binding.topicId, { onChange(binding.copy(topicId = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(tr.text("telegram.topicId")) }, singleLine = true)
            OutlinedTextField(binding.initiator, { onChange(binding.copy(initiator = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(tr.text("telegram.initiator")) }, singleLine = true)
            OutlinedTextField(binding.locale, { onChange(binding.copy(locale = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(tr.text("telegram.locale")) }, singleLine = true)
            TelegramToggle(tr.text("telegram.enabled"), binding.enabled) { onChange(binding.copy(enabled = it)) }
            binding.routes.forEachIndexed { index, route ->
                fun update(value: TelegramRouteDraft) = onChange(binding.copy(routes = binding.routes.toMutableList().apply { this[index] = value }))
                HorizontalDivider()
                TelegramChoice(tr.text("telegram.agent"), route.agentId.value, target?.agents.orEmpty()
                    .filter { candidate -> binding.routes.withIndex().none { (i, item) -> i != index && item.agentId == candidate.agentId } }
                    .map { it.agentId.value to it.name }) { update(route.copy(agentId = AgentDefinition.Id(it))) }
                OutlinedTextField(route.trigger, { update(route.copy(trigger = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(tr.text("telegram.trigger")) }, singleLine = true)
                Text(tr.text("telegram.contextPercent", "percent" to route.contextPercent))
                Slider(route.contextPercent.toFloat(), { update(route.copy(contextPercent = it.toInt())) }, valueRange = 1f..80f, steps = 78)
                TelegramToggle(tr.text("telegram.writeAllowed"), route.writeAllowed) { update(route.copy(writeAllowed = it)) }
                Text(tr.text("telegram.writeHint"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(route.instruction, { update(route.copy(instruction = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(tr.text("telegram.additionalInstruction")) }, minLines = 2)
                TextButton(enabled = binding.routes.size > 1, onClick = { onChange(binding.copy(routes = binding.routes.filterIndexed { i, _ -> i != index })) }) {
                    Text(tr.text("telegram.removeAgent"))
                }
            }
            val nextAgent = target?.agents?.firstOrNull { candidate -> binding.routes.none { it.agentId == candidate.agentId } }
            TextButton(enabled = nextAgent != null, onClick = { nextAgent?.let { onChange(binding.copy(routes = binding.routes + TelegramRouteDraft(it.agentId, it.name))) } }) {
                Text(tr.text("telegram.addAgent"))
            }
            if (binding.routes.withIndex().any { (index, item) -> item.trigger.isNotBlank() && binding.routes.drop(index + 1).any {
                it.trigger.isNotBlank() && (it.trigger.contains(item.trigger, true) || item.trigger.contains(it.trigger, true))
            } }) Text(tr.text("telegram.overlappingAliases"), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRemove) { Text(tr.text("telegram.removeGroup")) }
        }
    }
}

@Composable
private fun TelegramProfileEditor(profile: TelegramBotProfile, busy: Boolean, serverError: String?, onDismiss: () -> Unit, onSave: (TelegramProfileUpdate) -> Unit) {
    val tr = LocalTranslation.current
    var name by remember(profile) { mutableStateOf(profile.name) }
    var description by remember(profile) { mutableStateOf(profile.description) }
    var shortDescription by remember(profile) { mutableStateOf(profile.shortDescription) }
    var avatar by remember(profile) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr.text("telegram.profile")) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr.text("telegram.profileGlobal"))
            serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr.text("telegram.name")) })
            OutlinedTextField(description, { description = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr.text("telegram.longDescription")) }, minLines = 2)
            OutlinedTextField(shortDescription, { shortDescription = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr.text("telegram.shortDescription")) })
            OutlinedTextField(avatar, { avatar = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr.text("telegram.avatarArtifact")) })
            Text(tr.text("telegram.avatarHint"), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(enabled = !busy && name.isNotBlank() && name.length <= 64 && description.length <= 512 && shortDescription.length <= 120,
        onClick = { onSave(TelegramProfileUpdate(name, description, shortDescription, avatar.trim().takeIf { it.isNotEmpty() }?.let(Artifact::Id))) }) {
        Text(tr.text("security.users.save"))
    } }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr.text("agents.cancel")) } })
}

@Composable
private fun TelegramToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun TelegramChoice(label: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = options.isNotEmpty()) { Text(options.firstOrNull { it.first == selected }?.second ?: selected.ifBlank { "—" }) }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(id) }) }
            }
        }
    }
}
