package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.tool.*
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.session.BasicGromozekaDialog

@Composable
internal fun AgentToolAccessDialog(
    policy: ToolAccessPolicy,
    catalog: List<AgentToolCatalogEntry>,
    catalogError: String?,
    onChange: (ToolAccessPolicy) -> Unit,
    onDismiss: () -> Unit,
) {
    val translation = LocalTranslation.current
    var query by remember { mutableStateOf("") }
    fun replace(entries: Set<ToolSelector>) = onChange(when (policy) {
        is ToolAccessPolicy.AllowOnly -> policy.copy(entries = entries)
        is ToolAccessPolicy.DenyListed -> policy.copy(entries = entries)
    })
    BasicGromozekaDialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 760.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(translation.text("agents.tools.access"), style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(policy is ToolAccessPolicy.AllowOnly, { onChange(ToolAccessPolicy.AllowOnly(policy.entries)) }, { Text(translation.text("agents.tools.allowlist")) })
                    FilterChip(policy is ToolAccessPolicy.DenyListed, { onChange(ToolAccessPolicy.DenyListed(policy.entries)) }, { Text(translation.text("agents.tools.denylist")) })
                }
                Text(translation.text(if (policy is ToolAccessPolicy.AllowOnly) "agents.tools.allowlist_help" else "agents.tools.denylist_help"), style = MaterialTheme.typography.bodySmall)
                catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(query, { query = it }, label = { Text(translation.text("agents.tools.search")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(policy.entries.toList()) { selector ->
                        val entry = catalog.filter { it.matches(selector) }.maxByOrNull { it.variant ?: 0 }
                        val qualifiedName = entry?.name ?: (selector as? ToolSelector.ByName)?.tool
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(qualifiedName?.let { "${it.source} / ${it.name}" } ?: (selector as ToolSelector.ExactRevision).fingerprint.value)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(selector is ToolSelector.ByName, { all ->
                                        val replacement = if (all) ToolSelector.ByName(checkNotNull(qualifiedName))
                                            else ToolSelector.ExactRevision(checkNotNull(entry).fingerprint)
                                        replace(policy.entries.mapTo(linkedSetOf()) { if (it == selector) replacement else it })
                                    }, enabled = entry != null)
                                    Text(translation.text("agents.tools.all_revisions"), Modifier.weight(1f))
                                    if (selector is ToolSelector.ExactRevision) Text("${entry?.variant ?: "?"} · ${selector.fingerprint.value.take(10)}", style = MaterialTheme.typography.bodySmall)
                                    TextButton({ replace(policy.entries - selector) }) { Text(translation.text("agents.tools.remove")) }
                                }
                                if (entry == null || !entry.available) Text(translation.text("agents.tools.unavailable"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item {
                        HorizontalDivider()
                        Text(translation.text("agents.tools.catalog"), style = MaterialTheme.typography.titleSmall)
                    }
                    items(catalog.filter { "${it.name.source} ${it.name.name} ${it.modelName}".contains(query, ignoreCase = true) }) { entry ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${entry.name.source} / ${entry.name.name}", style = MaterialTheme.typography.bodyMedium)
                                Text("${entry.modelName} · ${entry.variant ?: translation.text("agents.tools.native")} · ${entry.fingerprint.value.take(10)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(translation.text(if (policy.allows(entry.name, entry.fingerprint.value)) "agents.tools.allowed" else "agents.tools.blocked"), style = MaterialTheme.typography.labelSmall)
                            TextButton({ replace(policy.entries + entry.selector(false)) }, enabled = policy.entries.none(entry::matches)) {
                                Text(translation.text("agents.tools.add"))
                            }
                        }
                    }
                }
                TextButton(onDismiss, Modifier.align(Alignment.End)) { Text(translation.text("agents.tools.done")) }
            }
        }
    }
}
