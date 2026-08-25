package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.service.AiUsageReportService
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

private enum class UsagePeriod(
    val label: String,
    val duration: Duration?,
) {
    Day("24h", 24.hours),
    Week("7d", 7.days),
    Month("30d", 30.days),
    All("All", null),
}

private data class UsageFilters(
    val provider: String = "",
    val model: String = "",
    val project: String = "",
    val agent: String = "",
    val conversation: String = "",
    val purpose: String = "",
)

@Composable
internal fun AiUsageSettings(service: AiUsageReportService) {
    var selectedPeriod by remember { mutableStateOf(UsagePeriod.Week) }
    var editableFilters by remember { mutableStateOf(UsageFilters()) }
    var appliedFilters by remember { mutableStateOf(editableFilters) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<TokenUsageStatistics.Report?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val query = remember(selectedPeriod, appliedFilters, refreshKey) {
        val now = Clock.System.now()
        TokenUsageStatistics.ReportQuery(
            from = selectedPeriod.duration?.let { now - it },
            to = now,
            provider = appliedFilters.provider.asFilter(),
            modelId = appliedFilters.model.asFilter(),
            projectId = appliedFilters.project.asFilter()?.let(Project::Id),
            agentDefinitionId = appliedFilters.agent.asFilter()?.let(AgentDefinition::Id),
            conversationId = appliedFilters.conversation.asFilter()?.let(Conversation::Id),
            runtimePurpose = appliedFilters.purpose.asFilter(),
        )
    }

    LaunchedEffect(query) {
        loading = true
        error = null
        runCatching { service.getReport(query) }
            .onSuccess { report = it }
            .onFailure { error = it.message ?: it::class.simpleName ?: "Unknown error" }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("AI usage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Installation-wide consumption and estimated direct API cost",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { refreshKey++ }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh usage")
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsagePeriod.entries.forEach { period ->
                FilterChip(
                    selected = selectedPeriod == period,
                    onClick = { selectedPeriod = period },
                    label = { Text(period.label) },
                )
            }
        }

        UsageFilterFields(
            filters = editableFilters,
            onFiltersChange = { editableFilters = it },
            onApply = { appliedFilters = editableFilters },
        )

        when {
            loading && report == null -> CircularProgressIndicator()
            error != null -> Text(
                "Could not load usage: $error",
                color = MaterialTheme.colorScheme.error,
            )
            report != null -> UsageReportContent(requireNotNull(report), loading)
        }
    }
}

@Composable
private fun UsageFilterFields(
    filters: UsageFilters,
    onFiltersChange: (UsageFilters) -> Unit,
    onApply: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Filters", style = MaterialTheme.typography.titleMedium)
            UsageFilterField("Provider", filters.provider) { onFiltersChange(filters.copy(provider = it)) }
            UsageFilterField("Model", filters.model) { onFiltersChange(filters.copy(model = it)) }
            UsageFilterField("Project ID", filters.project) { onFiltersChange(filters.copy(project = it)) }
            UsageFilterField("Agent ID", filters.agent) { onFiltersChange(filters.copy(agent = it)) }
            UsageFilterField("Conversation ID", filters.conversation) {
                onFiltersChange(filters.copy(conversation = it))
            }
            UsageFilterField("Runtime purpose", filters.purpose) { onFiltersChange(filters.copy(purpose = it)) }
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                Text("Apply filters")
            }
        }
    }
}

@Composable
private fun UsageFilterField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UsageReportContent(
    report: TokenUsageStatistics.Report,
    refreshing: Boolean,
) {
    val totals = report.totals
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Summary", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (refreshing) Text("Refreshing...", style = MaterialTheme.typography.bodySmall)
            UsageMetric("Calls", totals.callCount.toLong().grouped())
            UsageMetric("Input", "${totals.totalInputTokens.grouped()} tokens")
            UsageMetric("Output", "${totals.totalOutputTokens.grouped()} tokens")
            UsageMetric("Cache writes", "${totals.cacheCreationTokens.grouped()} tokens")
            UsageMetric("Cache reads", "${totals.cacheReadTokens.grouped()} tokens")
            UsageMetric("Thinking", "${totals.thinkingTokens.grouped()} tokens")
            UsageMetric("Estimated direct API cost", totals.estimatedCostNanoUsd.usd())
            UsageMetric("Priced calls", totals.pricedCallCount.toLong().grouped())
            UsageMetric("Unpriced calls", totals.unpricedCallCount.toLong().grouped())
            if (totals.unpricedCallCount > 0) {
                Text(
                    "Subscription, compatible, local, and unknown models stay explicitly unpriced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    UsageBreakdown("Provider / model", report.byProviderAndModel)
    UsageBreakdown("Runtime purpose", report.byRuntimePurpose)
    UsageBreakdown("Project", report.byProject)
    UsageBreakdown("Agent", report.byAgent)
    UsageBreakdown("Conversation", report.byConversation)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recent calls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (report.recentCalls.isEmpty()) {
            Text("No matching calls", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        report.recentCalls.forEachIndexed { index, call ->
            if (index > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${call.provider} / ${call.modelId}", fontWeight = FontWeight.SemiBold)
                Text(
                    "${call.runtimePurpose} · ${call.executionTarget}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${call.timestamp} · ${call.totalTokens.toLong().grouped()} tokens · ${call.price?.estimatedCostNanoUsd?.usd() ?: "unpriced"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun UsageBreakdown(
    title: String,
    entries: List<TokenUsageStatistics.Breakdown>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (entries.isEmpty()) {
            Text("No data", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entries.take(20).forEach { entry ->
            UsageMetric(
                entry.key,
                "${entry.totals.totalInputTokens.grouped()} in · " +
                    "${entry.totals.totalOutputTokens.grouped()} out · " +
                    entry.totals.estimatedCostNanoUsd.usd(),
            )
        }
        if (entries.size > 20) {
            Text(
                "${entries.size - 20} more groups hidden; narrow the filters to inspect them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageMetric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun String.asFilter(): String? = trim().takeIf(String::isNotEmpty)

private fun Long.grouped(): String {
    val digits = toString()
    val start = digits.length % 3
    return buildString {
        if (start > 0) append(digits.take(start))
        digits.drop(start).chunked(3).forEachIndexed { index, chunk ->
            if (isNotEmpty() || index > 0) append(',')
            append(chunk)
        }
    }
}

private fun Long.usd(): String {
    val whole = this / NANO_USD_PER_USD
    val fraction = ((this % NANO_USD_PER_USD) / 100_000L).toString().padStart(4, '0')
    return "\$$whole.$fraction"
}

private const val NANO_USD_PER_USD = 1_000_000_000L
