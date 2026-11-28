package com.gromozeka.presentation.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.presentation.services.LiveInterpreterController
import com.gromozeka.presentation.services.LiveInterpreterPhase
import com.gromozeka.presentation.services.LiveNativeTranslation
import com.gromozeka.presentation.services.LiveSourceBlock
import com.gromozeka.presentation.services.LiveTranscriptEntry
import com.gromozeka.presentation.services.LiveTranslationState
import com.gromozeka.presentation.services.parseLiveLanguageHints
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.data.Translation

@Composable
fun LiveInterpreterScreen(
    controller: LiveInterpreterController,
    aiConfigurationProvider: AiConfigurationProvider,
    isCompactLayout: Boolean = false,
) {
    val translation = LocalTranslation.current
    val state by controller.state.collectAsState()
    val textState by controller.translations.state.collectAsState()
    val catalog by aiConfigurationProvider.snapshotFlow.collectAsState()
    fun modelsFor(purpose: AiRuntimeAssignment.Purpose) = catalog?.catalog?.modelConfigurations.orEmpty().filter { model ->
        val runtime = catalog?.resolveRuntimeIfAvailable(AiRuntimeSelection(model.id))
        val allowedKinds = when (purpose) {
            AiRuntimeAssignment.Purpose.LIVE_TRANSCRIPTION -> setOf(AiConnection.Kind.OPENAI_API, AiConnection.Kind.SONIOX_API)
            AiRuntimeAssignment.Purpose.LIVE_TRANSLATION -> setOf(AiConnection.Kind.OPENAI_API)
            else -> emptySet()
        }
        catalog?.supportsPurpose(model, purpose) == true &&
            runtime?.connection?.kind in allowedKinds &&
            runtime?.connection?.executionTarget == AiExecutionTarget.Server
    }
    val models = modelsFor(AiRuntimeAssignment.Purpose.LIVE_TRANSCRIPTION)
    val textModels = modelsFor(AiRuntimeAssignment.Purpose.LIVE_TRANSLATION)
    val preferredId = state.selectedModelId ?: catalog?.catalog
        ?.runtimeSelectionFor(AiRuntimeAssignment.Purpose.LIVE_TRANSCRIPTION)?.modelConfigurationId
    val preferredTextId = textState.modelId ?: catalog?.catalog
        ?.runtimeSelectionFor(AiRuntimeAssignment.Purpose.LIVE_TRANSLATION)?.modelConfigurationId
    val selectedModel = models.firstOrNull { it.id == preferredId }
    val selectedTextModel = textModels.firstOrNull { it.id == preferredTextId }
    val selectedRuntime = selectedModel?.let { catalog?.resolveRuntimeIfAvailable(AiRuntimeSelection(it.id)) }
    val nativeTranslationSupported = selectedRuntime?.connection?.kind == AiConnection.Kind.SONIOX_API
    val nativeColumnLanguage = state.activeNativeTranslationLanguage
        ?: state.nativeTranslationLanguage.takeIf { nativeTranslationSupported }
    val textLanguages = textState.languages.filterNot { it == nativeColumnLanguage }
    val languageHintsValid = remember(state.languageHintsText) {
        try { parseLiveLanguageHints(state.languageHintsText); true } catch (_: IllegalArgumentException) { false }
    }
    val names = remember { Translation.builtIn.values.associate { it.languageCode.lowercase() to it.languageName } }
    val currentText = textState.takeIf { it.sessionId == state.translationSessionId }
    val rows = remember(state.entries, currentText?.blocks, currentText?.gaps) { liveRows(state.entries, currentText) }
    val listState = rememberLazyListState()
    LaunchedEffect(rows) {
        val tail = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        if (rows.isNotEmpty() && !listState.isScrollInProgress && (tail == null || tail >= rows.lastIndex - 1)) {
            listState.scrollToItem(rows.lastIndex)
        }
    }
    Column(
        Modifier.fillMaxSize().padding(if (isCompactLayout) 8.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(translation.text("interpreter.title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.phase.isActive) {
                OutlinedButton(onClick = controller::stop, enabled = state.phase != LiveInterpreterPhase.STOPPING) {
                    Text(translation.text("interpreter.stop"))
                }
            } else {
                Button(onClick = { selectedModel?.let {
                    controller.start(AiRuntimeSelection(it.id), state.nativeTranslationLanguage.takeIf { nativeTranslationSupported })
                } },
                    enabled = selectedModel != null && languageHintsValid && controller.unavailableReason == null) {
                    Text(translation.text(if (state.phase == LiveInterpreterPhase.PAUSED) "interpreter.resume" else "interpreter.start"))
                }
            }
        }
        Text(translation.text("interpreter.description"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.languageHintsText, onValueChange = controller::setLanguages, enabled = !state.phase.isActive,
            label = { Text(translation.text("interpreter.languages")) },
            supportingText = { Text(translation.text(if (languageHintsValid) "interpreter.languagesHint" else "interpreter.languagesInvalid")) },
            isError = !languageHintsValid, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr), modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveModelMenu(translation.text("interpreter.model"), models, selectedModel, !state.phase.isActive, controller::selectModel)
            if (nativeTranslationSupported) {
                LiveNativeTranslationMenu(state.nativeTranslationLanguage, names,
                    !state.phase.isActive && state.phase != LiveInterpreterPhase.PAUSED,
                    controller::setNativeTranslationLanguage)
            }
            LiveModelMenu(translation.text("interpreter.translationModel"), textModels, selectedTextModel, true, controller.translations::selectModel)
            LiveLanguageMenu(textState.languages, names, controller.translations::toggleLanguage, controller.translations::addLanguage)
        }
        if (models.isEmpty()) Text(translation.text("interpreter.noModel"), color = MaterialTheme.colorScheme.error)
        if (textModels.isEmpty() && textLanguages.isNotEmpty()) {
            Text(translation.text("interpreter.noTranslationModel"), color = MaterialTheme.colorScheme.error)
        }
        controller.unavailableReason?.let { Text(it.resolve(translation), color = MaterialTheme.colorScheme.error) }
        currentText?.sourceError?.let { Text(it.resolve(translation), color = MaterialTheme.colorScheme.error) }

        Card(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val contentWidth = maxOf(maxWidth, 250.dp * (textLanguages.size + 1 + if (nativeColumnLanguage == null) 0 else 1))
                Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    Column(Modifier.width(contentWidth).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f).padding(12.dp)) {
                                Text(translation.text("interpreter.original"), style = MaterialTheme.typography.titleMedium)
                            }
                            nativeColumnLanguage?.let { language ->
                                Column(Modifier.weight(1f).padding(12.dp)) {
                                    Text((names[language] ?: language) + " · Soniox", style = MaterialTheme.typography.titleMedium)
                                    Text(translation.text("interpreter.nativeTranslation"), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            textLanguages.forEach { language ->
                                key(language) {
                                    val column = currentText?.columns?.get(language)
                                    Column(Modifier.weight(1f).padding(12.dp)) {
                                        Text(names[language] ?: language, style = MaterialTheme.typography.titleMedium)
                                        val done = currentText?.blocks.orEmpty().count { column?.values?.get(it.id)?.sourceText == it.text }
                                        Text(translation.text("interpreter.translationProgress", "done" to done,
                                            "total" to currentText?.blocks.orEmpty().size), style = MaterialTheme.typography.labelSmall)
                                        column?.error?.let {
                                            Text(it.resolve(translation), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                            TextButton(onClick = { controller.translations.retry(language) }) {
                                                Text(translation.text("interpreter.retryTranslation"))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                        if (rows.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(translation.text("interpreter.noText"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else SelectionContainer {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(rows, key = { "${state.translationSessionId}:${it.id}" }) { row ->
                                    when (row) {
                                        is LiveRow.Gap -> Card(colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                        ), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                            Text(row.message.resolve(translation), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                                        }
                                        is LiveRow.Block -> {
                                            Row(Modifier.fillMaxWidth()) {
                                                LiveTextCell(row.block.text,
                                                    row.block.speakerId?.let { translation.text("interpreter.speaker", "speaker" to it) },
                                                    true, Modifier.weight(1f))
                                                nativeColumnLanguage?.let {
                                                    val native = nativeTranslationFor(row.block, state.nativeTranslations)
                                                    val status = when {
                                                        native == null -> "interpreter.translationWaiting"
                                                        native.isFinal -> "interpreter.translationFixed"
                                                        else -> "interpreter.translationDraft"
                                                    }
                                                    LiveTextCell(native?.text.orEmpty(), translation.text(status),
                                                        native?.isFinal == true, Modifier.weight(1f))
                                                }
                                                textLanguages.forEach { language ->
                                                    key(language) {
                                                        val column = currentText?.columns?.get(language)
                                                        val value = column?.values?.get(row.block.id)
                                                        val fixed = currentText?.isFixed(language, row.index) == true
                                                        val status = when {
                                                            value == null || value.sourceText != row.block.text -> "interpreter.translationWaiting"
                                                            fixed -> "interpreter.translationFixed"
                                                            else -> "interpreter.translationDraft"
                                                        }
                                                        LiveTextCell(value?.text.orEmpty(), translation.text(status), fixed, Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                            HorizontalDivider()
                                        }
                                        is LiveRow.Pending -> {
                                            val details = buildList {
                                                row.segment.speakerId?.let { add(translation.text("interpreter.speaker", "speaker" to it)) }
                                                if (!row.segment.isFinal) add(translation.text(
                                                    if (row.segment.interrupted) "interpreter.interrupted" else "interpreter.partial"))
                                            }.joinToString(" • ").ifBlank { null }
                                            Row(Modifier.fillMaxWidth()) {
                                                LiveTextCell(row.segment.text, details,
                                                    row.segment.isFinal, Modifier.weight(1f))
                                                nativeColumnLanguage?.let {
                                                    val native = state.nativeTranslations[row.segment.id]
                                                    val status = when {
                                                        native == null -> if (row.segment.isFinal) "interpreter.translationWaiting" else null
                                                        native.isFinal -> "interpreter.translationFixed"
                                                        else -> "interpreter.translationDraft"
                                                    }
                                                    LiveTextCell(native?.text.orEmpty(), status?.let(translation::text),
                                                        native?.isFinal == true, Modifier.weight(1f))
                                                }
                                                textLanguages.forEach { language ->
                                                    key(language) {
                                                        LiveTextCell("", if (row.segment.isFinal) translation.text("interpreter.translationWaiting") else "—",
                                                            false, Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                            HorizontalDivider()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(state.status.resolve(translation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun nativeTranslationFor(
    block: LiveSourceBlock,
    translations: Map<String, LiveNativeTranslation>,
): LiveNativeTranslation? {
    val values = block.segmentIds.mapNotNull(translations::get)
    if (values.isEmpty()) return null
    return LiveNativeTranslation(
        text = values.joinToString(separator = "\n") { it.text },
        isFinal = values.size == block.segmentIds.size && values.all { it.isFinal },
        targetLanguage = values.map { it.targetLanguage }.distinct().single(),
        sourceLanguage = values.mapNotNull { it.sourceLanguage }.distinct().singleOrNull(),
        speakerId = block.speakerId,
    )
}

@Composable
private fun LiveTextCell(text: String, status: String?, fixed: Boolean, modifier: Modifier) {
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            color = if (fixed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun LiveModelMenu(label: String, models: List<AiModelConfiguration>, selected: AiModelConfiguration?,
                          enabled: Boolean, onSelect: (AiModelConfiguration.Id) -> Unit) {
    val translation = LocalTranslation.current
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled && models.isNotEmpty()) {
            Text(label + ": " + (selected?.displayName ?: translation.text("interpreter.selectModel")))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { model -> DropdownMenuItem(text = { Text(model.displayName) },
                onClick = { onSelect(model.id); open = false }) }
        }
    }
}

@Composable
private fun LiveNativeTranslationMenu(
    selected: String?,
    names: Map<String, String>,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    val translation = LocalTranslation.current
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    val customCode = try { parseLiveLanguageHints(custom).singleOrNull() } catch (_: IllegalArgumentException) { null }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text(translation.text("interpreter.nativeTranslation") + ": " +
                (selected?.let { names[it] ?: it } ?: translation.text("interpreter.translationOff")))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 480.dp)) {
            DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected == null, onCheckedChange = null)
                Text(translation.text("interpreter.translationOff"))
            } }, onClick = { onSelect(null); open = false })
            (names.keys + listOfNotNull(selected)).distinct().sorted().forEach { language ->
                DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = language == selected, onCheckedChange = null)
                    Text((names[language] ?: language) + " ($language)")
                } }, onClick = { onSelect(language); open = false })
            }
            HorizontalDivider()
            OutlinedTextField(value = custom, onValueChange = { custom = it }, singleLine = true,
                label = { Text(translation.text("interpreter.addTranslationLanguage")) },
                isError = custom.isNotBlank() && customCode == null,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.width(280.dp).padding(8.dp))
            TextButton(enabled = customCode != null, onClick = {
                customCode?.let { onSelect(it); custom = ""; open = false }
            }) { Text(translation.text("interpreter.addLanguage")) }
        }
    }
}

@Composable
private fun LiveLanguageMenu(selected: List<String>, names: Map<String, String>, onToggle: (String) -> Unit, onAdd: (String) -> Unit) {
    val translation = LocalTranslation.current
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    val customCode = try { parseLiveLanguageHints(custom).singleOrNull() } catch (_: IllegalArgumentException) { null }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(translation.text("interpreter.translationLanguages") + ": " +
                if (selected.isEmpty()) translation.text("interpreter.translationOff") else selected.joinToString(", "))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 480.dp)) {
            (names.keys + selected).distinct().sorted().forEach { language ->
                DropdownMenuItem(enabled = language in selected || selected.size < 16,
                    text = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = language in selected, onCheckedChange = null)
                    Text((names[language] ?: language) + " ($language)")
                } }, onClick = {
                    onToggle(language)
                })
            }
            HorizontalDivider()
            OutlinedTextField(value = custom, onValueChange = { custom = it }, singleLine = true,
                label = { Text(translation.text("interpreter.addTranslationLanguage")) },
                isError = custom.isNotBlank() && customCode == null,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.width(280.dp).padding(8.dp))
            TextButton(enabled = customCode != null && selected.size < 16, onClick = {
                customCode?.let { onAdd(it); custom = "" }
            }) { Text(translation.text("interpreter.addLanguage")) }
        }
    }
}

internal sealed interface LiveRow {
    val id: String
    data class Block(val index: Int, val block: LiveSourceBlock) : LiveRow { override val id = "block:${block.id}" }
    data class Pending(val segment: LiveTranscriptEntry.Segment) : LiveRow { override val id = "pending:${segment.id}" }
    data class Gap(override val id: String, val message: LocalizedText) : LiveRow
}

internal fun liveRows(entries: List<LiveTranscriptEntry>, state: LiveTranslationState?): List<LiveRow> {
    val positions = entries.withIndex().associate { it.value.id to it.index }
    val blocks = state?.blocks.orEmpty().withIndex().filter { (_, block) -> block.segmentIds.all { it in positions } }
    val consumed = blocks.flatMap { it.value.segmentIds }.toSet()
    val rows = mutableListOf<Pair<Int, LiveRow>>()
    blocks.forEach { (index, block) -> rows += block.segmentIds.minOf { positions.getValue(it) } to LiveRow.Block(index, block) }
    entries.forEachIndexed { index, entry -> when (entry) {
        is LiveTranscriptEntry.Gap -> rows += index to LiveRow.Gap("gap:${entry.id}", entry.message)
        is LiveTranscriptEntry.Segment -> if (entry.id !in consumed) rows += index to LiveRow.Pending(entry)
    } }
    return rows.sortedWith(compareBy<Pair<Int, LiveRow>> { it.first }.thenBy { (it.second as? LiveRow.Block)?.index ?: 0 }).map { it.second }
}
