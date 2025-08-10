package com.gromozeka.bot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.settings.Settings

@Composable
fun SettingsPanel(
    isVisible: Boolean,
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = expandHorizontally(),
        exit = shrinkHorizontally(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .width(400.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                    start = 0.dp // No left padding - global window padding is enough
                )
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close settings")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Settings content
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Audio Settings
                    SettingsGroup(title = "Audio") {
                        SwitchSettingItem(
                            label = "Enable Text-to-Speech",
                            description = "Convert AI responses to speech",
                            value = settings.enableTts,
                            onValueChange = { onSettingsChange(settings.copy(enableTts = it)) }
                        )
                        
                        SwitchSettingItem(
                            label = "Enable Speech-to-Text",
                            description = "Convert voice input to text",
                            value = settings.enableStt,
                            onValueChange = { onSettingsChange(settings.copy(enableStt = it)) }
                        )
                        
                        // Only show TTS speed if TTS is enabled
                        if (settings.enableTts) {
                            SliderSettingItem(
                                label = "TTS Speed",
                                description = "Speech rate: 0.25x (slowest) to 4.0x (fastest)",
                                value = settings.ttsSpeed,
                                min = 0.25f,
                                max = 4.0f,
                                step = 0.25f,
                                valueFormat = "%.2fx",
                                onValueChange = { onSettingsChange(settings.copy(ttsSpeed = it)) }
                            )
                        }
                        
                        // Only show STT language if STT is enabled
                        if (settings.enableStt) {
                            DropdownSettingItem(
                                label = "STT Language",
                                description = "Speech recognition language",
                                value = settings.sttMainLanguage,
                                options = listOf("en", "ru", "es", "fr", "de", "zh", "ja"),
                                onValueChange = { onSettingsChange(settings.copy(sttMainLanguage = it)) }
                            )
                        }
                    }
                    
                    // Input Settings
                    SettingsGroup(title = "Input") {
                        SwitchSettingItem(
                            label = "Auto-send messages",
                            description = "Send messages immediately after voice input",
                            value = settings.autoSend,
                            enabled = settings.enableStt, // Only enable if STT is enabled
                            onValueChange = { onSettingsChange(settings.copy(autoSend = it)) }
                        )
                        
                        SwitchSettingItem(
                            label = "Global PTT Hotkey",
                            description = "Enable push-to-talk from anywhere (Cmd+Shift+Space)",
                            value = settings.globalPttHotkeyEnabled,
                            onValueChange = { onSettingsChange(settings.copy(globalPttHotkeyEnabled = it)) }
                        )
                        
                        // Only show mute option if global PTT is enabled
                        if (settings.globalPttHotkeyEnabled) {
                            SwitchSettingItem(
                                label = "Mute system audio during PTT",
                                description = "Prevent audio feedback when recording",
                                value = settings.muteSystemAudioDuringPTT,
                                onValueChange = { onSettingsChange(settings.copy(muteSystemAudioDuringPTT = it)) }
                            )
                        }
                    }
                    
                    // AI Settings
                    SettingsGroup(title = "AI") {
                        DropdownSettingItem(
                            label = "Claude Model",
                            description = "AI model to use for responses",
                            value = settings.claudeModel,
                            options = listOf("sonnet", "haiku", "opus"),
                            onValueChange = { onSettingsChange(settings.copy(claudeModel = it)) }
                        )
                        
                        SwitchSettingItem(
                            label = "Include current time",
                            description = "Add timestamp to prompts for time-aware responses",
                            value = settings.includeCurrentTime,
                            onValueChange = { onSettingsChange(settings.copy(includeCurrentTime = it)) }
                        )
                    }
                    
                    // UI Settings
                    SettingsGroup(title = "Interface") {
                        SwitchSettingItem(
                            label = "Show system messages",
                            description = "Display system notifications in chat (errors always shown)",
                            value = settings.showSystemMessages,
                            onValueChange = { onSettingsChange(settings.copy(showSystemMessages = it)) }
                        )
                    }
                    
                    // Developer Settings
                    SettingsGroup(title = "Developer") {
                        SwitchSettingItem(
                            label = "Show original JSON",
                            description = "Display raw API responses in chat",
                            value = settings.showOriginalJson,
                            onValueChange = { onSettingsChange(settings.copy(showOriginalJson = it)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            
            content()
        }
    }
}

@Composable
private fun SwitchSettingItem(
    label: String,
    description: String,
    value: Boolean,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
        }
        
        Switch(
            checked = value,
            onCheckedChange = onValueChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SliderSettingItem(
    label: String,
    description: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    valueFormat: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueFormat.format(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            steps = if (step > 0f) ((max - min) / step).toInt() - 1 else 0
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSettingItem(
    label: String,
    description: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        
        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}