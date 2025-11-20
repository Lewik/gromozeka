package com.gromozeka.presentation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.viewmodel.LoadingViewModel
import kotlinx.coroutines.launch

@Composable
fun LoadingScreen(
    loadingViewModel: LoadingViewModel,
    onComplete: () -> Unit
) {
    val loadingState by loadingViewModel.loadingState.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            loadingViewModel.initialize()
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingViewModel.LoadingState.Complete) {
            onComplete()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.padding(48.dp)
            ) {
                Image(
                    painter = painterResource("logos/logo-128x128.png"),
                    contentDescription = "Gromozeka Logo",
                    modifier = Modifier.size(96.dp)
                )

                Text(
                    text = "Gromozeka",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )

                when (val state = loadingState) {
                    is LoadingViewModel.LoadingState.Initializing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Initializing...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    is LoadingViewModel.LoadingState.LoadingMCP -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Loading MCP servers",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${state.serverName} (${state.current}/${state.total})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is LoadingViewModel.LoadingState.Error -> {
                        Text(
                            text = "Error: ${state.message}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    is LoadingViewModel.LoadingState.Complete -> {
                        Text(
                            text = "Ready!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
