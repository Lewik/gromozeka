package com.gromozeka.presentation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.presentation.services.translation.data.Translation

@Composable
fun TokenStatisticsTable(
    tokenStats: TokenUsageStatistics.ThreadTotals?,
    modifier: Modifier = Modifier,
) {
    if (tokenStats == null) {
        return
    }

    val translation = LocalTranslation.current.runtime
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header with expand/collapse button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    translation.tokenUsageTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) {
                        translation.collapseDescription
                    } else {
                        translation.expandDescription
                    }
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))

            // Context Window
            val contextWindow = tokenStats.contextWindowTokens
            val currentContext = tokenStats.currentContextSize

            if (currentContext != null && contextWindow != null) {
                val percentage = (currentContext.toFloat() / contextWindow * 100).toInt()
                Text(
                    "${translation.contextWindowLabel}: $percentage% " +
                        "(${currentContext.formatWithCommas()} / ${contextWindow.formatWithCommas()})",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
            } else if (currentContext != null) {
                Text(
                    "${translation.contextWindowLabel}: ${currentContext.formatWithCommas()} ${translation.tokensLabel}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (tokenStats.recentCalls.isNotEmpty()) {
                Text(
                    "${translation.recentTurnsLabel}: ${tokenStats.recentCalls.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                val hasThinking = tokenStats.recentCalls.any { it.thinkingTokens > 0 }

                // Table
                TokenTable(
                    calls = tokenStats.recentCalls,
                    totals = tokenStats,
                    hasThinking = hasThinking,
                    translation = translation,
                )
            }
            }
        }
    }
}

@Composable
private fun TokenTable(
    calls: List<TokenUsageStatistics>,
    totals: TokenUsageStatistics.ThreadTotals,
    hasThinking: Boolean,
    translation: Translation.RuntimeTranslation,
) {
    Column {
        // Header
        TokenTableHeader(hasThinking, translation)

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Rows
        calls.forEach { call ->
            TokenTableRow(
                prompt = call.promptTokens,
                cacheCr = call.cacheCreationTokens,
                cacheRd = call.cacheReadTokens,
                compl = call.completionTokens,
                think = if (hasThinking) call.thinkingTokens else null,
                isTotal = false
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Total row
        TokenTableRow(
            prompt = totals.totalPromptTokens,
            cacheCr = totals.totalCacheCreationTokens,
            cacheRd = totals.totalCacheReadTokens,
            compl = totals.totalCompletionTokens,
            think = if (hasThinking) totals.totalThinkingTokens else null,
            isTotal = true
        )
    }
}

@Composable
private fun TokenTableHeader(
    hasThinking: Boolean,
    translation: Translation.RuntimeTranslation,
) {
    // Level 1: Main sections (INPUT | OUTPUT | Total)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // INPUT section
        Row(modifier = Modifier.weight(1f)) {
            Text(
                translation.inputLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // OUTPUT section
        Row(modifier = Modifier.weight(0.6f)) {
            Text(
                translation.outputLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Total column
        Text(
            translation.totalLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.End
        )
    }

    Spacer(modifier = Modifier.height(2.dp))

    // Level 2: Sub-sections (Prompt, Cache | Compl, Think)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // INPUT subsections
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text(
                translation.promptLabel,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
            Text(
                translation.cacheLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(2f),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // OUTPUT subsections (just labels, no grouping needed)
        Row(modifier = Modifier.weight(0.6f), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text(
                translation.completionLabel,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
            if (hasThinking) {
                Text(
                    translation.thinkingLabel,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Total column (empty)
        Spacer(modifier = Modifier.width(60.dp))
    }

    Spacer(modifier = Modifier.height(2.dp))

    // Level 3: Cache details (Cr, Rd)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // INPUT cache columns
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            Spacer(modifier = Modifier.weight(1f)) // Prompt space
            Text(
                translation.createLabel,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
            Text(
                translation.readLabel,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // OUTPUT columns (empty)
        Spacer(modifier = Modifier.weight(0.6f))

        Spacer(modifier = Modifier.width(8.dp))

        // Total column (empty)
        Spacer(modifier = Modifier.width(60.dp))
    }
}

@Composable
private fun TokenTableRow(
    prompt: Int,
    cacheCr: Int,
    cacheRd: Int,
    compl: Int,
    think: Int?,
    isTotal: Boolean,
) {
    val total = prompt + cacheCr + cacheRd + compl + (think ?: 0)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // INPUT columns
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text(
                prompt.formatWithCommas(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
            Text(
                cacheCr.formatWithCommas(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                color = if (cacheCr > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                cacheRd.formatWithCommas(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                color = if (cacheRd > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // OUTPUT columns
        Row(modifier = Modifier.weight(0.6f), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text(
                compl.formatWithCommas(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
            if (think != null) {
                Text(
                    think.formatWithCommas(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                    color = if (think > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Total column
        Text(
            total.formatWithCommas(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.End
        )
    }
}

private fun Int.formatWithCommas(): String =
    this.toString().reversed().chunked(3).joinToString(",").reversed()
