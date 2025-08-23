package com.gromozeka.bot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.ui.components.*
import com.gromozeka.bot.ui.viewmodel.ContextsPanelViewModel
import kotlinx.coroutines.launch

@Composable
fun ContextsPanel(
    isVisible: Boolean,
    onClose: () -> Unit,
    viewModel: ContextsPanelViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    
    AnimatedVisibility(
        visible = isVisible,
        enter = expandHorizontally(
            animationSpec = tween(300, easing = EaseOutCubic),
            expandFrom = Alignment.End
        ),
        exit = shrinkHorizontally(
            animationSpec = tween(300, easing = EaseInCubic),
            shrinkTowards = Alignment.End  
        ),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.width(480.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Enhanced header with statistics
                ContextsPanelHeader(
                    onClose = onClose,
                    contextsCount = uiState.contexts.size,
                    recentContextsCount = uiState.contexts.count { it.isRecentlyUsed },
                    isLoading = uiState.isLoading
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Advanced toolbar with search and filtering
                ContextsPanelToolbar(
                    searchQuery = uiState.searchQuery,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    sortBy = uiState.sortBy,
                    onSortByChange = viewModel::updateSortOption,
                    showOnlyRecent = uiState.showOnlyRecent,
                    onToggleRecentFilter = viewModel::toggleRecentFilter,
                    onExtractContexts = {
                        scope.launch {
                            viewModel.extractContextsFromCurrentTab()
                        }
                    },
                    onRefresh = {
                        scope.launch {
                            viewModel.loadContexts()
                        }
                    },
                    isLoading = uiState.isLoading
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Error display
                uiState.error?.let { error ->
                    ErrorCard(
                        error = error,
                        onDismiss = viewModel::clearError,
                        onRetry = {
                            scope.launch {
                                viewModel.loadContexts()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Enhanced contexts list with animations and states
                ContextsList(
                    contexts = uiState.filteredContexts,
                    operationInProgress = uiState.operationInProgress,
                    onStartContext = { context ->
                        scope.launch {
                            viewModel.startContextInNewTab(context)
                        }
                    },
                    onViewContext = { context ->
                        scope.launch {
                            viewModel.openContextInIDE(context)
                        }
                    },
                    onDeleteContext = { context ->
                        scope.launch {
                            viewModel.deleteContext(context)
                        }
                    },
                    isLoading = uiState.isLoading,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}