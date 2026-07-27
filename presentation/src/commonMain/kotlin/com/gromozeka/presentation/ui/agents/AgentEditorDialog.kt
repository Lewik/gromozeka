package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentTemplate
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.presentation.ui.session.BasicGromozekaDialog

data class AgentEditorValue(
    val name: String,
    val description: String?,
    val prompts: List<Prompt.Id>,
    val skills: List<AgentSkill.Id>,
    val runtimeSelection: AiRuntimeSelection,
    val runtimeOverrides: AiRuntimeOverrides,
    val tools: List<String>,
)

@Composable
fun AgentEditorDialog(
    agent: AgentDefinition?,
    template: AgentTemplate?,
    prompts: List<Prompt>,
    skills: List<AgentSkill>,
    modelConfigurations: List<AiModelConfiguration>,
    initialPromptIds: List<Prompt.Id>,
    defaultRuntimeSelection: AiRuntimeSelection,
    onSave: (AgentEditorValue) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(agent?.name ?: template?.name.orEmpty()) }
    var description by remember {
        mutableStateOf(agent?.description ?: template?.description.orEmpty())
    }
    var selectedPromptIds by remember {
        mutableStateOf(agent?.prompts ?: initialPromptIds)
    }
    var selectedSkillIds by remember { mutableStateOf(agent?.skills.orEmpty()) }
    var modelId by remember {
        mutableStateOf(
            agent?.runtimeSelection?.modelConfigurationId
                ?: template?.runtimeSelection?.modelConfigurationId
                ?: defaultRuntimeSelection.modelConfigurationId
        )
    }
    val initialOverrides = agent?.runtimeOverrides ?: template?.runtimeOverrides ?: AiRuntimeOverrides()
    var maxOutputTokens by remember {
        mutableStateOf(initialOverrides.maxOutputTokens?.toString().orEmpty())
    }
    var reasoningMode by remember { mutableStateOf(initialOverrides.reasoning?.mode) }
    var reasoningEffort by remember { mutableStateOf(initialOverrides.reasoning?.effort) }
    var reasoningDisplay by remember { mutableStateOf(initialOverrides.reasoning?.display) }
    var reasoningBudget by remember {
        mutableStateOf(initialOverrides.reasoning?.budgetTokens?.toString().orEmpty())
    }
    var toolsText by remember {
        mutableStateOf((agent?.tools ?: template?.tools.orEmpty()).joinToString("\n"))
    }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val includesRuntimeEnvironment = Prompt.Id("env") in selectedPromptIds
    val staticPromptIds = selectedPromptIds.filterNot { it.value == "env" }

    BasicGromozekaDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            modifier = Modifier.width(920.dp).heightIn(max = 860.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    when {
                        agent != null -> "Edit agent"
                        template != null -> "Create agent from template"
                        else -> "Create agent"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Model", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = { modelMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                modelConfigurations.firstOrNull { it.id == modelId }?.displayName
                                    ?: modelId.value
                            )
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false },
                        ) {
                            modelConfigurations.filter { it.enabled }.forEach { configuration ->
                                DropdownMenuItem(
                                    text = {
                                        Text("${configuration.displayName} · ${configuration.providerModelId}")
                                    },
                                    onClick = {
                                        modelId = configuration.id
                                        modelMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Available prompts", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = includesRuntimeEnvironment,
                                onCheckedChange = { checked ->
                                    selectedPromptIds = if (checked) {
                                        selectedPromptIds + Prompt.Id("env")
                                    } else {
                                        selectedPromptIds.filterNot { it.value == "env" }
                                    }
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Include runtime environment")
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(prompts, key = { it.id.value }) { prompt ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = prompt.id in staticPromptIds,
                                        onCheckedChange = { checked ->
                                            selectedPromptIds = if (checked) {
                                                selectedPromptIds + prompt.id
                                            } else {
                                                selectedPromptIds.filterNot { it == prompt.id }
                                            }
                                        },
                                    )
                                    Column {
                                        Text(prompt.name)
                                        Text(
                                            if (prompt.type is Prompt.Type.Global) "Global" else "Project",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Prompt order", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(selectedPromptIds) { index, promptId ->
                                val title = if (promptId.value == "env") {
                                    "Runtime environment"
                                } else {
                                    prompts.firstOrNull { it.id == promptId }?.name ?: promptId.value
                                }
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("${index + 1}.", modifier = Modifier.width(30.dp))
                                        Text(title, modifier = Modifier.weight(1f))
                                        IconButton(
                                            onClick = {
                                                if (index > 0) {
                                                    selectedPromptIds = selectedPromptIds.swap(index, index - 1)
                                                }
                                            },
                                            enabled = index > 0,
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                        }
                                        IconButton(
                                            onClick = {
                                                if (index < selectedPromptIds.lastIndex) {
                                                    selectedPromptIds = selectedPromptIds.swap(index, index + 1)
                                                }
                                            },
                                            enabled = index < selectedPromptIds.lastIndex,
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (skills.isNotEmpty()) {
                    Text("Skill packages", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        skills.forEach { skill ->
                            FilterChip(
                                selected = skill.id in selectedSkillIds,
                                onClick = {
                                    selectedSkillIds = if (skill.id in selectedSkillIds) {
                                        selectedSkillIds - skill.id
                                    } else {
                                        selectedSkillIds + skill.id
                                    }
                                },
                                label = { Text(skill.name) },
                            )
                        }
                    }
                }

                Text("Runtime overrides", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxOutputTokens,
                        onValueChange = { maxOutputTokens = it },
                        label = { Text("Max output tokens") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    AgentNullableEnumDropdown("Mode", reasoningMode, AiReasoningMode.entries) {
                        reasoningMode = it
                    }
                    AgentNullableEnumDropdown("Effort", reasoningEffort, AiReasoningEffort.entries) {
                        reasoningEffort = it
                    }
                    AgentNullableEnumDropdown("Display", reasoningDisplay, AiReasoningDisplay.entries) {
                        reasoningDisplay = it
                    }
                    OutlinedTextField(
                        value = reasoningBudget,
                        onValueChange = { reasoningBudget = it },
                        label = { Text("Budget tokens") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = toolsText,
                    onValueChange = { toolsText = it },
                    label = { Text("Always-loaded tools, one per line") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        runCatching {
                            require(name.isNotBlank()) { "Name is required" }
                            require(selectedPromptIds.isNotEmpty()) {
                                "Select at least one prompt or runtime environment"
                            }
                            val reasoning = if (
                                reasoningMode != null ||
                                reasoningEffort != null ||
                                reasoningDisplay != null ||
                                reasoningBudget.isNotBlank()
                            ) {
                                AiReasoningConfig(
                                    mode = reasoningMode,
                                    effort = reasoningEffort,
                                    display = reasoningDisplay,
                                    budgetTokens = reasoningBudget.optionalPositiveInt("Reasoning budget"),
                                )
                            } else {
                                null
                            }
                            AgentEditorValue(
                                name = name.trim(),
                                description = description.trim().ifBlank { null },
                                prompts = selectedPromptIds,
                                skills = selectedSkillIds,
                                runtimeSelection = AiRuntimeSelection(modelId),
                                runtimeOverrides = AiRuntimeOverrides(
                                    maxOutputTokens = maxOutputTokens.optionalPositiveInt("Max output tokens"),
                                    reasoning = reasoning,
                                ),
                                tools = toolsText.lineSequence()
                                    .map(String::trim)
                                    .filter(String::isNotBlank)
                                    .distinct()
                                    .toList(),
                            )
                        }.onSuccess(onSave).onFailure { error = it.message }
                    }) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun <T : Enum<T>> AgentNullableEnumDropdown(
    label: String,
    value: T?,
    options: List<T>,
    onSelect: (T?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { expanded = true }) {
            Text(value?.name ?: "Default")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Default") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

private fun <T> List<T>.swap(first: Int, second: Int): List<T> =
    toMutableList().apply {
        val value = this[first]
        this[first] = this[second]
        this[second] = value
    }

private fun String.optionalPositiveInt(label: String): Int? {
    if (isBlank()) return null
    val parsed = trim().toIntOrNull() ?: error("$label must be an integer")
    require(parsed > 0) { "$label must be positive" }
    return parsed
}
