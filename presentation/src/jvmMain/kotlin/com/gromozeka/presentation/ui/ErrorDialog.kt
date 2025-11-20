package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import kotlin.system.exitProcess

@Composable
fun ErrorDialog(
    error: Throwable,
    onClose: () -> Unit
) {
    Window(
        onCloseRequest = onClose,
        title = "Gromozeka - Initialization Error"
    ) {
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Failed to start Gromozeka",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )

                    Text(
                        text = error.message ?: "Unknown error",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Divider()

                    Text(
                        text = "Stack trace:",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        val scrollState = rememberScrollState()
                        Text(
                            text = error.stackTraceToString(),
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(scrollState),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Button(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close Application")
                    }
                }
            }
        }
    }
}
