package com.gromozeka.presentation.ui.agents

import com.gromozeka.presentation.ui.aiLabel
import com.gromozeka.presentation.ui.LocalTranslation
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
    val translation = LocalTranslation.current
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
                    Text(translation.text("agents.skills.compatibility", "compatibility" to it), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    translation.text("agents.skills.package_hash", "hash" to skill.contentHash),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    translation.text("agents.skills.materialization", "policy" to skill.materializationPlan.policy.aiLabel(translation)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    skill.materializationPlan.reason.let { reason ->
                        if (reason == "Materialization policy was explicitly set by the user.") {
                            translation.text("agents.skills.explicit_policy_reason")
                        } else {
                            reason
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    skill.materializationPlan.analyzedAt?.let { analyzedAt ->
                        translation.text(
                            "agents.skills.analyzed_at",
                            "timestamp" to analyzedAt,
                            "modelId" to skill.materializationPlan.analyzedByModelConfigurationId?.value.orEmpty(),
                        )
                    } ?: translation.text("agents.skills.manual_policy"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    translation.text("agents.skills.materialization_description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onReanalyze, enabled = !updating) {
                        Text(translation.text("agents.skills.reanalyze"))
                    }
                    OutlinedButton(
                        onClick = {
                            onSetMaterializationPolicy(AgentSkill.MaterializationPlan.Policy.REQUIRED)
                        },
                        enabled = !updating &&
                            skill.materializationPlan.policy != AgentSkill.MaterializationPlan.Policy.REQUIRED,
                    ) {
                        Text(translation.text("agents.skills.require_files"))
                    }
                    OutlinedButton(
                        onClick = {
                            onSetMaterializationPolicy(AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED)
                        },
                        enabled = !updating &&
                            skill.materializationPlan.policy != AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED,
                    ) {
                        Text(translation.text("agents.skills.model_readable_only"))
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
                        Text(translation.text("agents.close"))
                    }
                }
            }
        }
    }
}
