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
import androidx.compose.runtime.rememberUpdatedState
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
    val labelKey: String,
    val duration: Duration?,
) {
    Day("ai.usage.period.day", 24.hours),
    Week("ai.usage.period.week", 7.days),
    Month("ai.usage.period.month", 30.days),
    All("ai.usage.period.all", null),
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
    val translation by rememberUpdatedState(LocalTranslation.current)
    var selectedPeriod by remember { mutableStateOf(UsagePeriod.Week) }
    var editableFilters by remember { mutableStateOf(UsageFilters()) }
    var appliedFilters by remember { mutableStateOf(editableFilters) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<TokenUsageStatistics.Report?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember(translation) { mutableStateOf<String?>(null) }

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
            .onFailure { error = it.message ?: it::class.simpleName ?: translation.text("ai.usage.unknown_error") }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(translation.text("ai.usage.title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    translation.text("ai.usage.description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { refreshKey++ }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = translation.text("ai.usage.refresh"))
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsagePeriod.entries.forEach { period ->
                FilterChip(
                    selected = selectedPeriod == period,
                    onClick = { selectedPeriod = period },
                    label = { Text(translation.text(period.labelKey)) },
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
                translation.text("ai.usage.load_failed", "error" to error.orEmpty()),
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
    val translation = LocalTranslation.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(translation.text("ai.usage.filters"), style = MaterialTheme.typography.titleMedium)
            UsageFilterField(translation.text("ai.usage.provider"), filters.provider) { onFiltersChange(filters.copy(provider = it)) }
            UsageFilterField(translation.text("ai.usage.model"), filters.model) { onFiltersChange(filters.copy(model = it)) }
            UsageFilterField(translation.text("ai.usage.project_id"), filters.project) { onFiltersChange(filters.copy(project = it)) }
            UsageFilterField(translation.text("ai.usage.agent_id"), filters.agent) { onFiltersChange(filters.copy(agent = it)) }
            UsageFilterField(translation.text("ai.usage.conversation_id"), filters.conversation) {
                onFiltersChange(filters.copy(conversation = it))
            }
            UsageFilterField(translation.text("ai.usage.runtime_purpose"), filters.purpose) { onFiltersChange(filters.copy(purpose = it)) }
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                Text(translation.text("ai.usage.apply_filters"))
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
    val translation = LocalTranslation.current
    val totals = report.totals
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(translation.text("ai.usage.summary"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (refreshing) Text(translation.text("ai.usage.refreshing"), style = MaterialTheme.typography.bodySmall)
            UsageMetric(translation.text("ai.usage.calls"), totals.callCount.toLong().grouped())
            UsageMetric(translation.text("ai.usage.input"), translation.plural("ai.usage.tokens", totals.totalInputTokens))
            UsageMetric(translation.text("ai.usage.output"), translation.plural("ai.usage.tokens", totals.totalOutputTokens))
            UsageMetric(translation.text("ai.usage.cache_writes"), translation.plural("ai.usage.tokens", totals.cacheCreationTokens))
            UsageMetric(translation.text("ai.usage.cache_reads"), translation.plural("ai.usage.tokens", totals.cacheReadTokens))
            UsageMetric(translation.text("ai.usage.thinking"), translation.plural("ai.usage.tokens", totals.thinkingTokens))
            UsageMetric(translation.text("ai.usage.estimated_cost"), totals.estimatedCostNanoUsd.usd())
            UsageMetric(translation.text("ai.usage.priced_calls"), totals.pricedCallCount.toLong().grouped())
            UsageMetric(translation.text("ai.usage.unpriced_calls"), totals.unpricedCallCount.toLong().grouped())
            if (totals.unpricedCallCount > 0) {
                Text(
                    translation.text("ai.usage.unpriced_description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    UsageBreakdown(translation.text("ai.usage.provider_model"), report.byProviderAndModel)
    UsageBreakdown(translation.text("ai.usage.runtime_purpose"), report.byRuntimePurpose)
    UsageBreakdown(translation.text("ai.usage.project"), report.byProject)
    UsageBreakdown(translation.text("ai.usage.agent"), report.byAgent)
    UsageBreakdown(translation.text("ai.usage.conversation"), report.byConversation)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(translation.text("ai.usage.recent_calls"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (report.recentCalls.isEmpty()) {
            Text(translation.text("ai.usage.no_matching_calls"), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    translation.plural(
                        "ai.usage.call_summary",
                        call.totalTokens.toLong(),
                        "timestamp" to call.timestamp,
                        "cost" to (call.price?.estimatedCostNanoUsd?.usd() ?: translation.text("ai.usage.unpriced")),
                    ),
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
    val translation = LocalTranslation.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (entries.isEmpty()) {
            Text(translation.text("ai.usage.no_data"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entries.take(20).forEach { entry ->
            UsageMetric(
                entry.key,
                translation.text(
                    "ai.usage.breakdown_summary",
                    "inputTokens" to entry.totals.totalInputTokens.grouped(),
                    "outputTokens" to entry.totals.totalOutputTokens.grouped(),
                    "cost" to entry.totals.estimatedCostNanoUsd.usd(),
                ),
            )
        }
        if (entries.size > 20) {
            Text(
                translation.plural("ai.usage.hidden_groups", (entries.size - 20).toLong()),
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
