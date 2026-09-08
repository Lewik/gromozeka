package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.viewmodel.LoadingViewModel
import kotlinx.coroutines.launch

@Composable
fun LoadingScreen(
    loadingViewModel: LoadingViewModel,
    onComplete: () -> Unit
) {
    val translation = LocalTranslation.current
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
                            text = translation.text("bootstrap.initializing"),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    is LoadingViewModel.LoadingState.LoadingMCP -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = translation.text("bootstrap.loadingMcp"),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = translation.text("bootstrap.progress", "name" to state.serverName, "current" to state.current, "total" to state.total),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is LoadingViewModel.LoadingState.Error -> {
                        Text(
                            text = translation.text("common.error", "error" to state.message),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    is LoadingViewModel.LoadingState.Complete -> {
                        Text(
                            text = translation.text("bootstrap.ready"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
