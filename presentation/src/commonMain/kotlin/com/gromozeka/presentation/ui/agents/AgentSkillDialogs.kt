package com.gromozeka.presentation.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
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
    onDismiss: () -> Unit,
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
