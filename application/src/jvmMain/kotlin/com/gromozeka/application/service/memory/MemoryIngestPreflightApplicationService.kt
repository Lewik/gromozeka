package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.memory.MemoryIngestBlock
import com.gromozeka.domain.model.memory.MemoryIngestPlan
import com.gromozeka.domain.model.memory.MemoryIngestPlanner
import com.gromozeka.domain.model.memory.MemoryIngestPlanningRequest
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryIngestSectionPlan
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.SettingsProvider
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.springframework.stereotype.Service

internal data class MemoryIngestPreflightResult(
    val contentHash: String,
    val plan: MemoryIngestPlan,
    val sections: List<MemoryIngestSection>,
    val llmCalls: List<MemoryRun.LlmCallTiming> = emptyList(),
)

@Service
internal class MemoryIngestPreflightApplicationService(
    private val aiRuntimeProvider: AiRuntimeProvider,
    private val settingsProvider: SettingsProvider,
    private val llmCallObservers: List<MemoryRunLlmCallObserver>,
) {
    private val engine = MemoryIngestPreflightEngine()

    suspend fun inspect(
        contentText: String,
        sourceLabel: String,
        runtimeContext: RuntimeEnvironmentContext,
        runtimeSystemPrompts: List<String>,
    ): MemoryIngestPreflightResult {
        return collectMemoryRunTimings(llmCallObservers) { timingCollector ->
            engine.inspect(
                contentText = contentText,
                sourceLabel = sourceLabel,
                planner = LlmMemoryIngestPlanner(
                    runtime = aiRuntimeProvider.getRuntime(
                        selection = settingsProvider.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE_INGEST_PLANNER),
                        workspaceRootPath = runtimeContext.workspaceRootPath,
                    ),
                    runtimeSystemPrompts = runtimeSystemPrompts,
                ),
            ).copy(llmCalls = timingCollector.snapshot())
        }
    }

    fun approve(
        contentText: String,
        plan: MemoryIngestPlan,
    ): MemoryIngestPreflightResult = engine.approve(contentText, plan)
}

internal class MemoryIngestPreflightEngine {
    suspend fun inspect(
        contentText: String,
        sourceLabel: String,
        planner: MemoryIngestPlanner,
    ): MemoryIngestPreflightResult {
        val normalizedText = contentText.normalizeMemoryIngestText()
        require(normalizedText.isNotBlank()) { "Memory ingest content is blank." }
        val contentHash = normalizedText.memoryIngestIdentityHash()
        val blocks = normalizedText.toMemoryIngestBlocks()
        val deterministicFailure = deterministicFailure(normalizedText, blocks)
        if (deterministicFailure != null) {
            return MemoryIngestPreflightResult(
                contentHash = contentHash,
                plan = MemoryIngestPlan(
                    decision = MemoryIngestPlan.Decision.NEEDS_USER_STRUCTURE,
                    reason = deterministicFailure,
                ),
                sections = emptyList(),
            )
        }
        val windowPlans = blocks.toPlanningWindows().mapIndexed { index, window ->
            planner.plan(
                MemoryIngestPlanningRequest(
                    sourceLabel = "$sourceLabel window ${index + 1}",
                    blocks = window,
                    maxSectionChars = MAX_MEMORY_INGEST_SECTION_CHARS,
                )
            )
        }
        val plan = aggregatePlans(windowPlans)
        return MemoryIngestPreflightResult(
            contentHash = contentHash,
            plan = plan,
            sections = normalizedText.materializeSections(blocks, plan),
        )
    }

    fun approve(
        contentText: String,
        plan: MemoryIngestPlan,
    ): MemoryIngestPreflightResult {
        require(plan.decision == MemoryIngestPlan.Decision.NEEDS_USER_CONFIRMATION) {
            "Only a proposed memory ingest structure can be explicitly approved."
        }
        val normalizedText = contentText.normalizeMemoryIngestText()
        val blocks = normalizedText.toMemoryIngestBlocks()
        plan.validateMaterializable(blocks)
        val approvedPlan = plan.copy(
            decision = MemoryIngestPlan.Decision.READY,
            reason = "User explicitly approved the proposed memory ingest structure. Original proposal: ${plan.reason}",
        )
        return MemoryIngestPreflightResult(
            contentHash = normalizedText.memoryIngestIdentityHash(),
            plan = approvedPlan,
            sections = normalizedText.materializeSections(blocks, approvedPlan),
        )
    }

    private fun deterministicFailure(
        normalizedText: String,
        blocks: List<MemoryIngestBlock>,
    ): String? = when {
        normalizedText.toByteArray(StandardCharsets.UTF_8).size.toLong() > MAX_MEMORY_REMEMBER_INPUT_BYTES ->
            "The source is too large for one memory ingest operation; max=$MAX_MEMORY_REMEMBER_INPUT_BYTES bytes. Split it into smaller structured sources."

        blocks.any { it.text.length > MAX_MEMORY_INGEST_SECTION_CHARS } -> {
            val block = blocks.first { it.text.length > MAX_MEMORY_INGEST_SECTION_CHARS }
            "Source block ${block.id} at lines ${block.startLine}-${block.endLine} is too large (${block.text.length} characters; max=$MAX_MEMORY_INGEST_SECTION_CHARS) and has no safe blank-line boundary. Split it into coherent paragraphs or sections."
        }

        else -> null
    }

    private fun aggregatePlans(plans: List<MemoryIngestPlan>): MemoryIngestPlan {
        require(plans.isNotEmpty()) { "Memory ingest planning returned no window plans." }
        val needsStructure = plans.firstOrNull { it.decision == MemoryIngestPlan.Decision.NEEDS_USER_STRUCTURE }
        if (needsStructure != null) {
            return MemoryIngestPlan(
                decision = MemoryIngestPlan.Decision.NEEDS_USER_STRUCTURE,
                reason = needsStructure.reason,
            )
        }
        val decision = if (plans.any { it.decision == MemoryIngestPlan.Decision.NEEDS_USER_CONFIRMATION }) {
            MemoryIngestPlan.Decision.NEEDS_USER_CONFIRMATION
        } else {
            MemoryIngestPlan.Decision.READY
        }
        return MemoryIngestPlan(
            decision = decision,
            sections = plans.flatMap { it.sections },
            reason = plans.joinToString(" ") { it.reason }.trim(),
        )
    }
}

private fun String.normalizeMemoryIngestText(): String =
    replace("\r\n", "\n")
        .replace('\r', '\n')

private fun String.memoryIngestIdentityHash(): String =
    lines()
        .joinToString("\n") { line -> if (line.isBlank()) "" else line }
        .trim()
        .sha256ForMemoryIngest()

private fun String.sha256ForMemoryIngest(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

internal fun String.toMemoryIngestBlocks(): List<MemoryIngestBlock> {
    val lines = normalizeMemoryIngestText().lines()
    val blocks = mutableListOf<MemoryIngestBlock>()
    var blockStart: Int? = null

    fun finishBlock(endExclusive: Int) {
        val start = blockStart ?: return
        val text = lines.subList(start, endExclusive).joinToString("\n")
        if (text.isNotBlank()) {
            blocks += MemoryIngestBlock(
                id = "b${blocks.size + 1}",
                startLine = start + 1,
                endLine = endExclusive,
                text = text,
            )
        }
        blockStart = null
    }

    lines.forEachIndexed { index, line ->
        if (line.isBlank()) {
            finishBlock(index)
        } else if (blockStart == null) {
            blockStart = index
        }
    }
    finishBlock(lines.size)
    require(blocks.isNotEmpty()) { "Memory ingest content has no non-blank blocks." }
    return blocks
}

private fun List<MemoryIngestBlock>.toPlanningWindows(): List<List<MemoryIngestBlock>> {
    val windows = mutableListOf<MutableList<MemoryIngestBlock>>()
    var current = mutableListOf<MemoryIngestBlock>()
    var currentChars = 0
    forEach { block ->
        val renderedChars = block.text.length + 120
        if (current.isNotEmpty() && currentChars + renderedChars > MAX_MEMORY_INGEST_PLANNER_WINDOW_CHARS) {
            windows += current
            current = mutableListOf()
            currentChars = 0
        }
        current += block
        currentChars += renderedChars
    }
    if (current.isNotEmpty()) windows += current
    return windows
}

private fun String.materializeSections(
    blocks: List<MemoryIngestBlock>,
    plan: MemoryIngestPlan,
): List<MemoryIngestSection> {
    if (plan.decision == MemoryIngestPlan.Decision.NEEDS_USER_STRUCTURE) return emptyList()
    plan.validateMaterializable(blocks)
    val lines = lines()
    val blocksById = blocks.associateBy { it.id }
    val processingGroups = if (plan.decision == MemoryIngestPlan.Decision.READY) {
        plan.sections.packMemoryIngestProcessingGroups(blocksById)
    } else {
        plan.sections.map(::listOf)
    }
    return processingGroups.mapIndexed { index, sectionGroup ->
        val sectionBlocks = sectionGroup.flatMap { section ->
            section.blockIds.map { blocksById.getValue(it) }
        }
        val startLine = sectionBlocks.first().startLine
        val endLine = sectionBlocks.last().endLine
        MemoryIngestSection(
            index = index + 1,
            headingPath = sectionGroup.processingHeadingPath(),
            startLine = startLine,
            endLine = endLine,
            text = lines.subList(startLine - 1, endLine).joinToString("\n"),
        )
    }
}

private fun List<MemoryIngestSectionPlan>.packMemoryIngestProcessingGroups(
    blocksById: Map<String, MemoryIngestBlock>,
): List<List<MemoryIngestSectionPlan>> {
    val groups = mutableListOf<List<MemoryIngestSectionPlan>>()
    var current = mutableListOf<MemoryIngestSectionPlan>()

    forEach { section ->
        val candidate = current + section
        val candidateBlocks = candidate.flatMap { plan ->
            plan.blockIds.map { blocksById.getValue(it) }
        }
        if (current.isNotEmpty() && candidateBlocks.materializedMemoryIngestChars() > MAX_MEMORY_INGEST_SECTION_CHARS) {
            groups.add(current)
            current = mutableListOf(section)
        } else {
            current.add(section)
        }
    }

    if (current.isNotEmpty()) groups.add(current)
    return groups
}

private fun List<MemoryIngestSectionPlan>.processingHeadingPath(): List<String> = when (size) {
    1 -> listOf(single().title)
    else -> listOf("${first().title} .. ${last().title}")
}

private fun List<MemoryIngestBlock>.materializedMemoryIngestChars(): Int =
    sumOf { it.text.length } + zipWithNext().sumOf { (left, right) -> right.startLine - left.endLine }

private fun MemoryIngestPlan.validateMaterializable(blocks: List<MemoryIngestBlock>) {
    require(decision != MemoryIngestPlan.Decision.NEEDS_USER_STRUCTURE) {
        "A needs-user-structure plan cannot be materialized."
    }
    val expectedIds = blocks.map { it.id }
    val actualIds = sections.flatMap { it.blockIds }
    require(actualIds == expectedIds) {
        "Memory ingest plan does not cover source blocks exactly once in original order."
    }
    val blockIndex = blocks.mapIndexed { index, block -> block.id to index }.toMap()
    sections.forEach { section ->
        val indices = section.blockIds.map { blockIndex.getValue(it) }
        require(indices.zipWithNext().all { (left, right) -> right == left + 1 }) {
            "Memory ingest section ${section.title} is not contiguous."
        }
        val sectionBlocks = section.blockIds.map { id -> blocks[blockIndex.getValue(id)] }
        val chars = sectionBlocks.materializedMemoryIngestChars()
        require(chars <= MAX_MEMORY_INGEST_SECTION_CHARS) {
            "Memory ingest section ${section.title} is too large: $chars > $MAX_MEMORY_INGEST_SECTION_CHARS."
        }
    }
}

internal const val MAX_MEMORY_INGEST_SECTION_CHARS = 8_000
private const val MAX_MEMORY_INGEST_PLANNER_WINDOW_CHARS = 48_000
