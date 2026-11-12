package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.ai.*
import com.gromozeka.shared.uuid.uuid7
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
internal fun AiTpmGroupsEditor(groups: List<AiTpmGroup>, configurations: List<AiModelConfiguration>,
    onChange: (List<AiTpmGroup>) -> Unit, onError: (String?) -> Unit) {
    val text = LocalTranslation.current
    var editing by remember { mutableStateOf<AiTpmGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text.text("ai.tpm.description"))
        OutlinedButton(onClick = { creating = true }) { Text(text.text("ai.tpm.new")) }
        groups.forEach { group ->
            CatalogEntityCard(group.displayName, group.key,
                listOf("${group.tokenLimit} / ${group.renewalPeriodSeconds}s", "${(group.quotaUsageFraction * 100).toInt()}%"),
                onEdit = { editing = group }, onDelete = {
                    if (configurations.any { configuration -> configuration.tpm?.let {
                        (it.group as? AiTpmGroupSelector.Fixed)?.groupId == group.id || it.fallbackGroupId == group.id
                    } == true }) onError(text.text("ai.tpm.in_use"))
                    else onChange(groups.filterNot { it.id == group.id })
                })
        }
    }
    if (creating || editing != null) {
        TpmGroupDialog(editing, { creating = false; editing = null }) { group ->
            if (groups.any { it.id != group.id && it.key == group.key }) onError(text.text("ai.tpm.duplicate_key"))
            else onChange(groups.filterNot { it.id == group.id } + group)
            creating = false; editing = null
        }
    }
}

@Composable
private fun TpmGroupDialog(existing: AiTpmGroup?, onDismiss: () -> Unit, onSave: (AiTpmGroup) -> Unit) {
    val text = LocalTranslation.current
    val id = remember { existing?.id ?: AiTpmGroup.Id(uuid7().toString()) }
    var key by remember { mutableStateOf(existing?.key.orEmpty()) }
    var name by remember { mutableStateOf(existing?.displayName.orEmpty()) }
    var limit by remember { mutableStateOf(existing?.tokenLimit?.toString().orEmpty()) }
    var period by remember { mutableStateOf((existing?.renewalPeriodSeconds ?: 60).toString()) }
    var fraction by remember { mutableStateOf(((existing?.quotaUsageFraction ?: 1.0) * 100).toString()) }
    var parallel by remember { mutableStateOf(existing?.allowParallelRequests ?: false) }
    var retry by remember { mutableStateOf((existing?.missingRetryDelaySeconds ?: 10).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(text.text("ai.tpm.group")) }, text = {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TpmField(key, { key = it }, "ai.tpm.key")
            TpmField(name, { name = it }, "ai.field.display_name")
            TpmField(limit, { limit = it }, "ai.tpm.limit")
            TpmField(period, { period = it }, "ai.tpm.period")
            TpmField(fraction, { fraction = it }, "ai.tpm.fraction")
            Text(text.text("ai.tpm.fraction_hint"), style = MaterialTheme.typography.bodySmall)
            TpmToggle(parallel, { parallel = it }, "ai.tpm.parallel")
            TpmField(retry, { retry = it }, "ai.tpm.retry_delay")
            Text(text.text("ai.tpm.refill_hint"), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = {
        runCatching { AiTpmGroup(id, key.trim(), name.trim(), limit.toLong(), period.toInt(), parallel, fraction.toDouble() / 100, retry.toInt()) }
            .onSuccess(onSave).onFailure { error = it.message }
    }) { Text(text.text("ai.action.apply")) } }, dismissButton = {
        TextButton(onClick = onDismiss) { Text(text.text("ai.action.cancel")) }
    })
}

internal class QuotaCommandDraft(initial: AiQuotaCommand?) {
    var executable by mutableStateOf(initial?.executable.orEmpty())
    var arguments by mutableStateOf(Json.encodeToString(initial?.arguments ?: emptyList<String>()))
    var timeout by mutableStateOf((initial?.timeoutSeconds ?: 10).toString())
    fun build() = AiQuotaCommand(executable.trim(), Json.decodeFromString<List<String>>(arguments), timeout.toInt())
}

internal class QuotaSourceDraft(initial: AiQuotaValueSource?) {
    var mode by mutableStateOf(if (initial is AiQuotaValueSource.Body) "body" else "header")
    var header by mutableStateOf((initial as? AiQuotaValueSource.Header)?.name.orEmpty())
    var jsonHeader by mutableStateOf((initial as? AiQuotaValueSource.Header)?.jsonPointer != null)
    var pointer by mutableStateOf(when (initial) {
        is AiQuotaValueSource.Body -> initial.pointer
        is AiQuotaValueSource.Header -> initial.jsonPointer.orEmpty()
        null -> ""
    })
    fun build(): AiQuotaValueSource = if (mode == "body") AiQuotaValueSource.Body(pointer)
        else AiQuotaValueSource.Header(header.trim(), if (jsonHeader) pointer else null)
}

internal class AiTpmPolicyDraft(initial: AiTpmPolicy?) {
    var enabled by mutableStateOf(initial != null)
    var groupMode by mutableStateOf(when (initial?.group) {
        is AiTpmGroupSelector.RequestValue -> "request"
        is AiTpmGroupSelector.Command -> "command"
        else -> "fixed"
    })
    var groupId by mutableStateOf((initial?.group as? AiTpmGroupSelector.Fixed)?.groupId)
    var pointer by mutableStateOf((initial?.group as? AiTpmGroupSelector.RequestValue)?.pointer ?: "/model")
    var fallback by mutableStateOf(initial?.fallbackGroupId)
    val selectorCommand = QuotaCommandDraft((initial?.group as? AiTpmGroupSelector.Command)?.command)
    var calculatorMode by mutableStateOf(if (initial?.calculator is AiQuotaCalculator.Command) "command" else "builtin")
    private val initialCalculator = initial?.calculator as? AiQuotaCalculator.BuiltIn ?: AiQuotaCalculator.BuiltIn()
    var preset by mutableStateOf(initialCalculator.preset)
    var charactersPerUnit by mutableStateOf(initialCalculator.charactersPerUnit.toString())
    var multiplier by mutableStateOf(initialCalculator.multiplier.toString())
    var overhead by mutableStateOf(initialCalculator.fixedOverhead.toString())
    var outputReserve by mutableStateOf(initialCalculator.outputReserve?.toString().orEmpty())
    val calculatorCommand = QuotaCommandDraft((initial?.calculator as? AiQuotaCalculator.Command)?.command)
    var feedbackMode by mutableStateOf(if (initial?.feedback is AiTpmFeedbackPolicy.Command) "command" else "fields")
    private val fields = initial?.feedback as? AiTpmFeedbackPolicy.Fields
    val remaining = QuotaSourceDraft(fields?.remaining)
    var retryEnabled by mutableStateOf(fields?.retryDelay != null)
    val retry = QuotaSourceDraft(fields?.retryDelay)
    var retryFormat by mutableStateOf(fields?.retryFormat ?: AiTpmFeedbackPolicy.RetryFormat.SECONDS)
    val feedbackCommand = QuotaCommandDraft((initial?.feedback as? AiTpmFeedbackPolicy.Command)?.command)

    fun build(): AiTpmPolicy? {
        if (!enabled) return null
        val selector = when (groupMode) {
            "fixed" -> AiTpmGroupSelector.Fixed(requireNotNull(groupId) { "Select a TPM group" })
            "request" -> AiTpmGroupSelector.RequestValue(pointer)
            "command" -> AiTpmGroupSelector.Command(selectorCommand.build())
            else -> error("Invalid TPM group selector")
        }
        val calculator = if (calculatorMode == "command") AiQuotaCalculator.Command(calculatorCommand.build()) else
            AiQuotaCalculator.BuiltIn(preset, charactersPerUnit.toDouble(), multiplier.toDouble(), overhead.toLong(), outputReserve.takeIf { it.isNotBlank() }?.toLong())
        val feedback = if (feedbackMode == "command") AiTpmFeedbackPolicy.Command(feedbackCommand.build()) else
            AiTpmFeedbackPolicy.Fields(remaining.build(), if (retryEnabled) retry.build() else null, retryFormat)
        return AiTpmPolicy(selector, calculator, feedback, fallback)
    }
}

@Composable
internal fun AiTpmPolicyEditor(draft: AiTpmPolicyDraft, groups: List<AiTpmGroup>, connection: AiConnection?, contextWindowTokens: Int? = null) {
    val text = LocalTranslation.current
    val uri = LocalUriHandler.current
    val supported = connection?.kind in setOf(AiConnection.Kind.OPENAI_API, AiConnection.Kind.OPENAI_COMPATIBLE,
        AiConnection.Kind.ANTHROPIC_API, AiConnection.Kind.OPENAI_SUBSCRIPTION)
    TpmToggle(draft.enabled, { draft.enabled = it }, "ai.tpm.enabled", enabled = supported || draft.enabled)
    if (!supported) Text(text.text("ai.tpm.http_only"), style = MaterialTheme.typography.bodySmall)
    if (!draft.enabled) return
    Text(text.text("ai.tpm.policy_hint"), style = MaterialTheme.typography.bodySmall)
    val fixedGroup = groups.find { it.id == draft.groupId }.takeIf { draft.groupMode == "fixed" }
    if (fixedGroup != null && contextWindowTokens != null && fixedGroup.tokenLimit * fixedGroup.quotaUsageFraction < contextWindowTokens)
        Text(text.text("ai.tpm.capacity_warning"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    LabeledDropdown(text.text("ai.tpm.selector"), draft.groupMode, listOf("fixed", "request", "command"),
        { text.text("ai.tpm.mode.$it") }, { draft.groupMode = it })
    if (draft.groupMode == "fixed") GroupPicker(draft.groupId, groups, "ai.tpm.group", "ai.tpm.select_group") { draft.groupId = it }
    if (draft.groupMode == "request") {
        TpmField(draft.pointer, { draft.pointer = it }, "ai.tpm.request_pointer")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("/model", "/reasoning/effort", "/reasoning_effort").forEach { pointer ->
                TextButton(onClick = { draft.pointer = pointer }) { Text(pointer) }
            }
        }
    }
    if (draft.groupMode == "command") QuotaCommandEditor(draft.selectorCommand)
    GroupPicker(draft.fallback, groups, "ai.tpm.fallback", "ai.tpm.fail") { draft.fallback = it }
    HorizontalDivider()
    LabeledDropdown(text.text("ai.tpm.calculator"), draft.calculatorMode, listOf("builtin", "command"),
        { text.text("ai.tpm.mode.$it") }, { draft.calculatorMode = it })
    if (draft.calculatorMode == "command") QuotaCommandEditor(draft.calculatorCommand) else {
        LabeledDropdown(text.text("ai.tpm.preset"), draft.preset, AiQuotaCalculator.Preset.entries,
            { text.text("ai.tpm.preset.${it.name}") }, { draft.preset = it })
        Text(draft.preset.formula, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { uri.openUri(draft.preset.sourceUrl) }) { Text(text.text("ai.tpm.source")) }
        Text(text.text("ai.tpm.estimate_hint"), style = MaterialTheme.typography.bodySmall)
        TpmField(draft.charactersPerUnit, { draft.charactersPerUnit = it }, "ai.tpm.chars")
        TpmField(draft.multiplier, { draft.multiplier = it }, "ai.tpm.multiplier")
        TpmField(draft.overhead, { draft.overhead = it }, "ai.tpm.overhead")
        if (draft.preset in setOf(AiQuotaCalculator.Preset.INPUT_PLUS_OUTPUT, AiQuotaCalculator.Preset.OPENAI_MAXIMUM))
            TpmField(draft.outputReserve, { draft.outputReserve = it }, "ai.tpm.output_reserve")
    }
    HorizontalDivider()
    LabeledDropdown(text.text("ai.tpm.feedback"), draft.feedbackMode, listOf("fields", "command"),
        { text.text("ai.tpm.mode.$it") }, { draft.feedbackMode = it })
    if (draft.feedbackMode == "command") QuotaCommandEditor(draft.feedbackCommand) else {
        Text(text.text("ai.tpm.remaining"), style = MaterialTheme.typography.titleSmall)
        QuotaSourceEditor(draft.remaining)
        TpmToggle(draft.retryEnabled, { draft.retryEnabled = it }, "ai.tpm.retry_feedback")
        if (draft.retryEnabled) {
            QuotaSourceEditor(draft.retry)
            LabeledDropdown(text.text("ai.tpm.retry_format"), draft.retryFormat, AiTpmFeedbackPolicy.RetryFormat.entries,
                { it.name }, { draft.retryFormat = it })
        }
    }
}

@Composable
private fun GroupPicker(value: AiTpmGroup.Id?, groups: List<AiTpmGroup>, label: String, empty: String, onSelect: (AiTpmGroup.Id?) -> Unit) {
    val text = LocalTranslation.current
    LabeledDropdown(text.text(label), value, listOf(null) + groups.map { it.id },
        { id -> if (id == null) text.text(empty) else groups.single { it.id == id }.let { "${it.displayName} · ${it.key}" } }, onSelect)
}

@Composable
private fun QuotaCommandEditor(draft: QuotaCommandDraft) {
    val text = LocalTranslation.current
    TpmField(draft.executable, { draft.executable = it }, "ai.tpm.executable")
    TpmField(draft.arguments, { draft.arguments = it }, "ai.tpm.arguments")
    TpmField(draft.timeout, { draft.timeout = it }, "ai.tpm.command_timeout")
    Text(text.text("ai.tpm.command_hint"), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun QuotaSourceEditor(draft: QuotaSourceDraft) {
    val text = LocalTranslation.current
    LabeledDropdown(text.text("ai.tpm.value_source"), draft.mode, listOf("header", "body"),
        { text.text("ai.tpm.mode.$it") }, { draft.mode = it })
    if (draft.mode == "header") {
        TpmField(draft.header, { draft.header = it }, "ai.tpm.header")
        TpmToggle(draft.jsonHeader, { draft.jsonHeader = it }, "ai.tpm.json_header")
    }
    if (draft.mode == "body" || draft.jsonHeader) TpmField(draft.pointer, { draft.pointer = it }, "ai.tpm.response_pointer")
}

@Composable
private fun TpmField(value: String, onChange: (String) -> Unit, label: String) {
    val text = LocalTranslation.current
    OutlinedTextField(value, onChange, label = { Text(text.text(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun TpmToggle(value: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    val text = LocalTranslation.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(value, onChange, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(text.text(label))
    }
}
