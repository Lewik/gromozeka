package com.gromozeka.presentation.ui.plan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.model.plan.PlanStep
import com.gromozeka.domain.model.plan.StepStatus
import com.gromozeka.presentation.ui.viewmodel.PlanPanelViewModel

/**
 * Plan management panel (right panel).
 * 
 * Displays plan list with search, expand/collapse, and CRUD operations.
 */
@Composable
fun PlanPanelComponent(
    viewModel: PlanPanelViewModel,
    isVisible: Boolean,
    onClose: () -> Unit
) {
    val plans by viewModel.plans.collectAsState()
    val expandedPlans by viewModel.expandedPlans.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    
    val showCreatePlanDialog by viewModel.showCreatePlanDialog.collectAsState()
    val showEditPlanDialog by viewModel.showEditPlanDialog.collectAsState()
    val showClonePlanDialog by viewModel.showClonePlanDialog.collectAsState()
    val showCreateStepDialog by viewModel.showCreateStepDialog.collectAsState()
    val showEditStepDialog by viewModel.showEditStepDialog.collectAsState()
    val showDeletePlanDialog by viewModel.showDeletePlanDialog.collectAsState()
    val showDeleteStepDialog by viewModel.showDeleteStepDialog.collectAsState()

    AnimatedVisibility(
        visible = isVisible,
        enter = expandHorizontally(),
        exit = shrinkHorizontally()
    ) {
        Surface(
            modifier = Modifier
                .width(400.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
        // Header with title, [+ New] button, and close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Plans",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.createPlan() },
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New")
                }
                
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.searchPlans(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search plans...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Error message
        error?.let { errorMessage ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        // Plan list
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (plans.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isBlank()) "No plans yet" else "No plans found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(plans, key = { it.id.value }) { plan ->
                    PlanItem(
                        plan = plan,
                        isExpanded = expandedPlans.containsKey(plan.id),
                        steps = expandedPlans[plan.id] ?: emptyList(),
                        onToggleExpanded = { viewModel.togglePlanExpanded(plan.id) },
                        onEdit = { viewModel.editPlan(plan.id) },
                        onDelete = { viewModel.deletePlan(plan.id) },
                        onClone = { viewModel.clonePlan(plan.id) },
                        onAddStep = { viewModel.addStep(plan.id) },
                        onEditStep = { viewModel.editStep(it) },
                        onDeleteStep = { viewModel.deleteStep(it) },
                        onToggleStepStatus = { viewModel.toggleStepStatus(it) },
                        onMoveStepUp = { viewModel.moveStepUp(it, plan.id) },
                        onMoveStepDown = { viewModel.moveStepDown(it, plan.id) }
                    )
                }
            }
        }
            }
        }
    }
    
    // Dialogs (outside AnimatedVisibility)
    CreatePlanDialog(
        isOpen = showCreatePlanDialog,
        isSaving = isSaving,
        onConfirm = { name, description, isTemplate ->
            viewModel.confirmCreatePlan(name, description, isTemplate)
        },
        onDismiss = { viewModel.dismissCreatePlanDialog() }
    )
    
    EditPlanDialog(
        plan = showEditPlanDialog,
        isSaving = isSaving,
        onConfirm = { planId, name, description, isTemplate ->
            viewModel.confirmEditPlan(planId, name, description, isTemplate)
        },
        onDismiss = { viewModel.dismissEditPlanDialog() }
    )
    
    ClonePlanDialog(
        plan = showClonePlanDialog,
        isSaving = isSaving,
        onConfirm = { planId, newName ->
            viewModel.confirmClonePlan(planId, newName)
        },
        onDismiss = { viewModel.dismissClonePlanDialog() }
    )
    
    CreateStepDialog(
        planIdAndParent = showCreateStepDialog,
        availableSteps = showCreateStepDialog?.let { (planId, _) ->
            expandedPlans[planId] ?: emptyList()
        } ?: emptyList(),
        isSaving = isSaving,
        onConfirm = { planId, instruction, parentId ->
            viewModel.confirmAddStep(planId, instruction, parentId)
        },
        onDismiss = { viewModel.dismissCreateStepDialog() }
    )
    
    EditStepDialog(
        step = showEditStepDialog,
        availableSteps = showEditStepDialog?.let { step ->
            expandedPlans[step.planId] ?: emptyList()
        } ?: emptyList(),
        isSaving = isSaving,
        onConfirm = { stepId, instruction, parentId ->
            viewModel.confirmEditStep(stepId, instruction, parentId)
        },
        onDismiss = { viewModel.dismissEditStepDialog() }
    )
    
    DeletePlanConfirmDialog(
        plan = showDeletePlanDialog,
        onConfirm = { planId ->
            viewModel.confirmDeletePlan(planId)
        },
        onDismiss = { viewModel.dismissDeletePlanDialog() }
    )
    
    DeleteStepConfirmDialog(
        step = showDeleteStepDialog,
        onConfirm = { stepId, planId ->
            viewModel.confirmDeleteStep(stepId, planId)
        },
        onDismiss = { viewModel.dismissDeleteStepDialog() }
    )
}

/**
 * Single plan item (collapsed or expanded).
 */
@Composable
private fun PlanItem(
    plan: Plan,
    isExpanded: Boolean,
    steps: List<PlanStep>,
    onToggleExpanded: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClone: () -> Unit,
    onAddStep: () -> Unit,
    onEditStep: (PlanStep.Id) -> Unit,
    onDeleteStep: (PlanStep.Id) -> Unit,
    onToggleStepStatus: (PlanStep.Id) -> Unit,
    onMoveStepUp: (PlanStep.Id) -> Unit,
    onMoveStepDown: (PlanStep.Id) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Plan header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = plan.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        
                        if (plan.isTemplate) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "Template",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    
                    if (plan.description.isNotBlank()) {
                        Text(
                            text = plan.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                // Action buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { onEdit() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    
                    IconButton(
                        onClick = { onClone() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Clone",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    
                    IconButton(
                        onClick = { onDelete() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            // Expanded: show steps
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                
                if (steps.isEmpty()) {
                    Text(
                        text = "No steps yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    // Sort steps by parent chain (root first, then children in order)
                    val sortedSteps = sortStepsByParentChain(steps)
                    
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sortedSteps.forEachIndexed { index, step ->
                            StepItem(
                                step = step,
                                isFirst = index == 0,
                                isLast = index == sortedSteps.size - 1,
                                onEdit = { onEditStep(step.id) },
                                onDelete = { onDeleteStep(step.id) },
                                onToggleStatus = { onToggleStepStatus(step.id) },
                                onMoveUp = { onMoveStepUp(step.id) },
                                onMoveDown = { onMoveStepDown(step.id) }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = { onAddStep() },
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Step", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Single step item with checkbox and reorder buttons.
 */
@Composable
private fun StepItem(
    step: PlanStep,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleStatus: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checkbox for status
        Checkbox(
            checked = step.status == StepStatus.DONE,
            onCheckedChange = { onToggleStatus() },
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Instruction text
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (step) {
                    is PlanStep.Text -> step.instruction
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            // Show result if exists
            if (step is PlanStep.Text && step.result != null) {
                Text(
                    text = "Result: ${step.result}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Move up button
            IconButton(
                onClick = { onMoveUp() },
                modifier = Modifier.size(28.dp),
                enabled = !isFirst
            ) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = "Move Up",
                    modifier = Modifier.size(16.dp),
                    tint = if (isFirst) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) 
                           else MaterialTheme.colorScheme.onSurface
                )
            }
            
            // Move down button
            IconButton(
                onClick = { onMoveDown() },
                modifier = Modifier.size(28.dp),
                enabled = !isLast
            ) {
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = "Move Down",
                    modifier = Modifier.size(16.dp),
                    tint = if (isLast) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) 
                           else MaterialTheme.colorScheme.onSurface
                )
            }
            
            IconButton(
                onClick = { onEdit() },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit",
                    modifier = Modifier.size(16.dp)
                )
            }
            
            IconButton(
                onClick = { onDelete() },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Sort steps by parent chain to display them in correct order.
 * 
 * Algorithm:
 * 1. Find root step (parentId == null)
 * 2. Follow the chain: each step's child is the one whose parentId points to it
 * 3. Build linear list from root to last child
 * 
 * This ensures steps are displayed in the order they are linked via parentId.
 */
private fun sortStepsByParentChain(steps: List<PlanStep>): List<PlanStep> {
    if (steps.isEmpty()) return emptyList()
    
    // Build map: stepId -> step
    val stepMap = steps.associateBy { it.id }
    
    // Build map: parentId -> list of children
    val childrenMap = steps.groupBy { it.parentId }
    
    // Find root step (no parent)
    val root = steps.firstOrNull { it.parentId == null }
        ?: return steps // Fallback: if no root found, return original order
    
    // Build ordered list by following the chain
    val result = mutableListOf<PlanStep>()
    var current: PlanStep? = root
    
    while (current != null) {
        result.add(current)
        // Find child: step whose parentId points to current step
        val children = childrenMap[current.id] ?: emptyList()
        current = children.firstOrNull() // Take first child (should be only one in linear chain)
    }
    
    return result
}
