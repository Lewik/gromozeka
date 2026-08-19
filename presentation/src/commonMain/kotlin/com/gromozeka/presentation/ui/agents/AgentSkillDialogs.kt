package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.presentation.ui.GromozekaMarkdown
import com.gromozeka.presentation.ui.session.BasicGromozekaDialog

@Composable
fun AgentSkillDetailsDialog(
    skill: AgentSkill,
    updating: Boolean,
    onDismiss: () -> Unit,
    onReanalyze: () -> Unit,
    onSetMaterializationPolicy: (AgentSkill.MaterializationPlan.Policy) -> Unit,
) {
    BasicGromozekaDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            modifier = Modifier.width(720.dp).heightIn(max = 760.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(skill.name, style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                skill.compatibility?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Compatibility: $it", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Package ${skill.contentHash}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Materialization: ${skill.materializationPlan.policy.name.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    skill.materializationPlan.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    skill.materializationPlan.analyzedAt?.let { analyzedAt ->
                        "Analyzed at $analyzedAt by ${skill.materializationPlan.analyzedByModelConfigurationId?.value}"
                    } ?: "Manual materialization policy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Actual workspace materialization availability is resolved per conversation from online Workers and project mounts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onReanalyze, enabled = !updating) {
                        Text("Reanalyze")
                    }
                    OutlinedButton(
                        onClick = {
                            onSetMaterializationPolicy(AgentSkill.MaterializationPlan.Policy.REQUIRED)
                        },
                        enabled = !updating &&
                            skill.materializationPlan.policy != AgentSkill.MaterializationPlan.Policy.REQUIRED,
                    ) {
                        Text("Require workspace files")
                    }
                    OutlinedButton(
                        onClick = {
                            onSetMaterializationPolicy(AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED)
                        },
                        enabled = !updating &&
                            skill.materializationPlan.policy != AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED,
                    ) {
                        Text("Model-readable only")
                    }
                    if (updating) {
                        CircularProgressIndicator()
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                GromozekaMarkdown(
                    content = skill.instructions,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
