package com.gromozeka.application.service.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

internal enum class MemoryDocumentType {
    MARKDOWN;

    companion object {
        fun parse(value: String?): MemoryDocumentType? {
            val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            return when (normalized) {
                "markdown", "md", "text/markdown" -> MARKDOWN
                else -> throw IllegalArgumentException("Unsupported memory document_type: $value. Supported: markdown.")
            }
        }
    }
}

internal enum class MemoryRememberInputKind {
    TEXT,
    FILE_PATH,
    RAW_URL,
}

internal data class MemoryRememberContentRequest(
    val text: String? = null,
    val filePath: String? = null,
    val rawUrl: String? = null,
    val documentType: String? = null,
    val title: String? = null,
    val sourceRef: String? = null,
)

internal data class MemoryResolvedRememberContent(
    val kind: MemoryRememberInputKind,
    val text: String,
    val documentType: MemoryDocumentType?,
    val title: String?,
    val sourceRef: String,
)

internal data class MemoryRememberDocumentResult(
    val documentType: MemoryDocumentType,
    val inputKind: MemoryRememberInputKind,
    val title: String?,
    val sourceRef: String,
    val parentSourceId: String,
    val sections: List<MarkdownDocumentSection>,
    val sectionResults: List<com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult>,
)

internal data class MemoryRememberDocumentQueuedResult(
    val runId: String,
    val documentType: MemoryDocumentType,
    val inputKind: MemoryRememberInputKind,
    val title: String?,
    val sourceRef: String,
    val parentSourceId: String,
    val sections: List<MarkdownDocumentSection>,
    val queueSize: Int,
)

internal data class MarkdownDocumentImport(
    val markdown: String,
    val title: String?,
    val sourceRef: String,
)

internal object MarkdownDocumentImportDetector {
    private val fileLineRegex = Regex("(?m)^File:\\s*(.+?)\\s*$")
    private val firstHeadingRegex = Regex("(?m)^#\\s+(.+?)\\s*$")

    fun detect(text: String): MarkdownDocumentImport? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("Memory ingestion source document.", ignoreCase = true)) return null
        val firstHeading = firstHeadingRegex.find(trimmed) ?: return null
        if (!trimmed.contains('\n')) return null

        val sourceRef = fileLineRegex.find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "pasted-markdown-document:${trimmed.sha256ForMarkdownImport().take(16)}"
        val title = firstHeading.groupValues
            .getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val markdown = trimmed.substring(firstHeading.range.first).trim()

        return MarkdownDocumentImport(
            markdown = markdown,
            title = title,
            sourceRef = sourceRef,
        )
    }
}

internal class MemoryRememberContentResolver(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    suspend fun resolve(request: MemoryRememberContentRequest): MemoryResolvedRememberContent {
        val nonBlankInputs = listOfNotNull(
            request.text?.takeIf { it.isNotBlank() }?.let { MemoryRememberInputKind.TEXT },
            request.filePath?.takeIf { it.isNotBlank() }?.let { MemoryRememberInputKind.FILE_PATH },
            request.rawUrl?.takeIf { it.isNotBlank() }?.let { MemoryRememberInputKind.RAW_URL },
        )
        require(nonBlankInputs.size == 1) {
            "memory_remember requires exactly one of text, file_path, or raw_url for provided content."
        }

        val kind = nonBlankInputs.single()
        val resolved = when (kind) {
            MemoryRememberInputKind.TEXT -> resolveText(request)
            MemoryRememberInputKind.FILE_PATH -> resolveFile(request)
            MemoryRememberInputKind.RAW_URL -> resolveRawUrl(request)
        }
        require(resolved.text.isNotBlank()) { "Resolved memory content is blank." }
        return resolved
    }

    private fun resolveText(request: MemoryRememberContentRequest): MemoryResolvedRememberContent {
        val text = request.text.orEmpty().trim()
        return MemoryResolvedRememberContent(
            kind = MemoryRememberInputKind.TEXT,
            text = text,
            documentType = MemoryDocumentType.parse(request.documentType),
            title = request.title?.trim()?.takeIf { it.isNotBlank() },
            sourceRef = request.sourceRef?.trim()?.takeIf { it.isNotBlank() } ?: "memory_remember:provided_text",
        )
    }

    private suspend fun resolveFile(request: MemoryRememberContentRequest): MemoryResolvedRememberContent =
        withContext(Dispatchers.IO) {
            val path = Path.of(request.filePath!!.trim()).toAbsolutePath().normalize()
            require(path.exists()) { "memory_remember file_path does not exist: $path" }
            require(path.isRegularFile()) { "memory_remember file_path is not a regular file: $path" }
            require(Files.size(path) <= MAX_INPUT_BYTES) {
                "memory_remember file_path is too large: ${Files.size(path)} bytes; max=$MAX_INPUT_BYTES."
            }
            MemoryResolvedRememberContent(
                kind = MemoryRememberInputKind.FILE_PATH,
                text = path.readText(),
                documentType = MemoryDocumentType.parse(request.documentType) ?: MemoryDocumentType.MARKDOWN,
                title = request.title?.trim()?.takeIf { it.isNotBlank() } ?: path.name,
                sourceRef = request.sourceRef?.trim()?.takeIf { it.isNotBlank() } ?: path.toString(),
            )
        }

    private suspend fun resolveRawUrl(request: MemoryRememberContentRequest): MemoryResolvedRememberContent =
        withContext(Dispatchers.IO) {
            val uri = URI.create(request.rawUrl!!.trim())
            require(uri.scheme == "http" || uri.scheme == "https") {
                "memory_remember raw_url supports only http/https."
            }
            val response = httpClient.send(
                HttpRequest.newBuilder(uri)
                    .GET()
                    .header("Accept", "text/plain, text/markdown, application/octet-stream;q=0.5")
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
            )
            require(response.statusCode() in 200..299) {
                "memory_remember raw_url returned HTTP ${response.statusCode()}: $uri"
            }
            val contentType = response.headers().firstValue("content-type").orElse("").lowercase()
            require(!contentType.contains("text/html")) {
                "memory_remember raw_url returned HTML; provide a raw text/markdown URL instead."
            }
            val body = response.body().trim()
            require(body.toByteArray(StandardCharsets.UTF_8).size <= MAX_INPUT_BYTES) {
                "memory_remember raw_url body is too large; max=$MAX_INPUT_BYTES bytes."
            }
            MemoryResolvedRememberContent(
                kind = MemoryRememberInputKind.RAW_URL,
                text = body,
                documentType = MemoryDocumentType.parse(request.documentType) ?: MemoryDocumentType.MARKDOWN,
                title = request.title?.trim()?.takeIf { it.isNotBlank() } ?: uri.path.substringAfterLast('/').ifBlank { uri.host },
                sourceRef = request.sourceRef?.trim()?.takeIf { it.isNotBlank() } ?: uri.toString(),
            )
        }

    private companion object {
        const val MAX_INPUT_BYTES = 10L * 1024L * 1024L
    }
}

internal data class MarkdownDocumentSection(
    val index: Int,
    val headingPath: List<String>,
    val startLine: Int,
    val endLine: Int,
    val text: String,
) {
    val headingLabel: String =
        headingPath.joinToString(" / ").ifBlank { "Document preamble" }
}

internal object MarkdownDocumentSlicer {
    private val headingRegex = Regex("^(#{1,6})\\s+(.+?)\\s*$")

    fun slice(
        markdown: String,
        maxSectionChars: Int = 8_000,
        maxSiblingSubtreesPerSection: Int = 2,
    ): List<MarkdownDocumentSection> {
        require(maxSectionChars > 0) { "maxSectionChars must be positive." }
        require(maxSiblingSubtreesPerSection > 0) { "maxSiblingSubtreesPerSection must be positive." }
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
        val tree = MarkdownTree.build(lines)
        val rawSections = tree.pack(maxSectionChars, maxSiblingSubtreesPerSection)
        return rawSections
            .flatMap { section -> section.splitOversized(maxSectionChars) }
            .mapIndexed { index, section -> section.copy(index = index + 1) }
    }

    fun splitForRetry(section: MarkdownDocumentSection): List<MarkdownDocumentSection> {
        val sectionLines = section.text.lines()
        val lineParts = if (sectionLines.size >= 2) {
            val splitIndex = sectionLines.headingSplitIndex()
                ?: sectionLines.blankLineSplitIndex()
                ?: sectionLines.lineSplitIndex()

            splitIndex?.let {
                listOf(
                    section.createRetryPart(
                        partNumber = 1,
                        totalParts = 2,
                        sectionLines = sectionLines,
                        startIndex = 0,
                        endIndex = it - 1,
                    ),
                    section.createRetryPart(
                        partNumber = 2,
                        totalParts = 2,
                        sectionLines = sectionLines,
                        startIndex = it,
                        endIndex = sectionLines.lastIndex,
                    ),
                ).filter { part -> part.text.isNotBlank() }
            }
        } else {
            null
        }

        return lineParts
            ?.takeIf { parts -> parts.size == 2 && parts.sumOf { it.text.length } < section.text.length + 16 }
            ?: section.splitRetryByText()
    }

    private fun MarkdownDocumentSection.splitOversized(maxSectionChars: Int): List<MarkdownDocumentSection> {
        if (text.length <= maxSectionChars) return listOf(this)

        val sectionLines = text.lines()
        val parts = mutableListOf<MarkdownDocumentSection>()
        var partStartIndex = 0
        var currentChars = 0

        sectionLines.forEachIndexed { index, line ->
            val lineChars = line.length + 1
            val canSplitHere = line.isBlank() && currentChars >= maxSectionChars / 2
            val mustSplitHere = currentChars + lineChars > maxSectionChars
            if ((canSplitHere || mustSplitHere) && index > partStartIndex) {
                parts += createPart(parts.size + 1, sectionLines, partStartIndex, index - 1)
                partStartIndex = index
                currentChars = 0
            }
            currentChars += lineChars
        }
        if (partStartIndex < sectionLines.size) {
            parts += createPart(parts.size + 1, sectionLines, partStartIndex, sectionLines.lastIndex)
        }
        return parts
            .filter { it.text.isNotBlank() }
            .flatMap { part -> part.splitOversizedText(maxSectionChars) }
    }

    private fun MarkdownDocumentSection.createPart(
        partNumber: Int,
        sectionLines: List<String>,
        startIndex: Int,
        endIndex: Int,
    ): MarkdownDocumentSection =
        copy(
            index = partNumber,
            headingPath = headingPath + "part $partNumber",
            startLine = startLine + startIndex,
            endLine = startLine + endIndex,
            text = sectionLines.subList(startIndex, endIndex + 1).joinToString("\n").trim(),
        )

    private fun MarkdownDocumentSection.createRetryPart(
        partNumber: Int,
        totalParts: Int,
        sectionLines: List<String>,
        startIndex: Int,
        endIndex: Int,
    ): MarkdownDocumentSection =
        copy(
            index = index * 10 + partNumber,
            headingPath = headingPath + "retry part $partNumber/$totalParts",
            startLine = startLine + startIndex,
            endLine = startLine + endIndex,
            text = sectionLines.subList(startIndex, endIndex + 1).joinToString("\n").trim(),
        )

    private fun MarkdownDocumentSection.splitRetryByText(): List<MarkdownDocumentSection> {
        if (text.length < 2) return emptyList()
        val splitIndex = text.findSplitBoundaryNear(text.length / 2, minIndex = 1)
            .takeIf { it in 1 until text.length }
            ?: return emptyList()

        return listOf(
            createRetryTextPart(
                partNumber = 1,
                totalParts = 2,
                startOffset = 0,
                endOffsetExclusive = splitIndex,
            ),
            createRetryTextPart(
                partNumber = 2,
                totalParts = 2,
                startOffset = splitIndex,
                endOffsetExclusive = text.length,
            ),
        ).filter { it.text.isNotBlank() }
            .takeIf { parts -> parts.size == 2 }
            ?: emptyList()
    }

    private fun MarkdownDocumentSection.splitOversizedText(maxSectionChars: Int): List<MarkdownDocumentSection> {
        if (text.length <= maxSectionChars) return listOf(this)

        val parts = mutableListOf<MarkdownDocumentSection>()
        var startOffset = 0
        while (startOffset < text.length) {
            val hardEnd = (startOffset + maxSectionChars).coerceAtMost(text.length)
            val endOffset = if (hardEnd == text.length) {
                text.length
            } else {
                text.findSplitBoundaryNear(hardEnd, minIndex = startOffset + maxSectionChars / 2)
                    ?.takeIf { it > startOffset }
                    ?: hardEnd
            }
            parts += createTextPart(
                partNumber = parts.size + 1,
                startOffset = startOffset,
                endOffsetExclusive = endOffset,
            )
            startOffset = endOffset
            while (startOffset < text.length && text[startOffset].isWhitespace()) {
                startOffset += 1
            }
        }
        return parts.filter { it.text.isNotBlank() }
    }

    private fun MarkdownDocumentSection.createTextPart(
        partNumber: Int,
        startOffset: Int,
        endOffsetExclusive: Int,
    ): MarkdownDocumentSection =
        copy(
            index = partNumber,
            headingPath = headingPath + "part $partNumber",
            startLine = lineNumberAtOffset(startOffset),
            endLine = lineNumberAtOffset((endOffsetExclusive - 1).coerceAtLeast(startOffset)),
            text = text.substring(startOffset, endOffsetExclusive).trim(),
        )

    private fun MarkdownDocumentSection.createRetryTextPart(
        partNumber: Int,
        totalParts: Int,
        startOffset: Int,
        endOffsetExclusive: Int,
    ): MarkdownDocumentSection =
        copy(
            index = index * 10 + partNumber,
            headingPath = headingPath + "retry part $partNumber/$totalParts",
            startLine = lineNumberAtOffset(startOffset),
            endLine = lineNumberAtOffset((endOffsetExclusive - 1).coerceAtLeast(startOffset)),
            text = text.substring(startOffset, endOffsetExclusive).trim(),
        )

    private fun MarkdownDocumentSection.lineNumberAtOffset(offset: Int): Int =
        startLine + text.take(offset.coerceIn(0, text.length)).count { it == '\n' }

    private fun String.findSplitBoundaryNear(targetIndex: Int, minIndex: Int): Int? {
        val boundedTarget = targetIndex.coerceIn(1, length - 1)
        val boundedMin = minIndex.coerceIn(1, boundedTarget)
        val search = substring(0, boundedTarget)

        listOf("\n\n", "\n").forEach { marker ->
            val index = search.lastIndexOf(marker)
            if (index >= boundedMin) return index + marker.length
        }

        for (index in boundedTarget - 1 downTo boundedMin) {
            val char = this[index]
            val next = getOrNull(index + 1)
            if ((char == '.' || char == '!' || char == '?' || char == ';' || char == ':') && next?.isWhitespace() == true) {
                return index + 1
            }
        }

        for (index in boundedTarget - 1 downTo boundedMin) {
            if (this[index].isWhitespace()) return index + 1
        }

        return boundedTarget.takeIf { it in 1 until length }
    }

    private fun List<String>.headingSplitIndex(): Int? =
        indices
            .drop(1)
            .filter { index -> index < lastIndex && headingRegex.matches(get(index)) }
            .minByOrNull { index -> kotlin.math.abs(index - size / 2) }

    private fun List<String>.blankLineSplitIndex(): Int? =
        indices
            .drop(1)
            .filter { index -> index < lastIndex && get(index).isBlank() }
            .minByOrNull { index -> kotlin.math.abs(index - size / 2) }
            ?.plus(1)
            ?.takeIf { it in 1..lastIndex }

    private fun List<String>.lineSplitIndex(): Int? =
        (size / 2).takeIf { it in 1..lastIndex }

    private data class MarkdownTree(
        val lines: List<String>,
        val root: Node,
    ) {
        fun pack(
            maxSectionChars: Int,
            maxSiblingSubtreesPerSection: Int,
        ): List<MarkdownDocumentSection> {
            val fullText = text(1, lines.size)
            if (fullText.isBlank()) return emptyList()
            if (fullText.length <= maxSectionChars) {
                return listOf(
                    MarkdownDocumentSection(
                        index = 1,
                        headingPath = emptyList(),
                        startLine = 1,
                        endLine = lines.size.coerceAtLeast(1),
                        text = fullText,
                    )
                )
            }

            val sections = mutableListOf<MarkdownDocumentSection>()
            root.ownSection()?.let { sections += it }
            sections += packChildren(root, maxSectionChars, maxSiblingSubtreesPerSection)
            return sections
        }

        private fun packNode(
            node: Node,
            maxSectionChars: Int,
            maxSiblingSubtreesPerSection: Int,
        ): List<MarkdownDocumentSection> {
            val subtreeText = text(node.startLine, node.endLine)
            if (subtreeText.length <= maxSectionChars) {
                return listOf(node.toSection(node.startLine, node.endLine, subtreeText))
            }

            val sections = mutableListOf<MarkdownDocumentSection>()
            node.ownSection()?.let { sections += it }
            sections += packChildren(node, maxSectionChars, maxSiblingSubtreesPerSection)
            return sections.ifEmpty { listOf(node.toSection(node.startLine, node.endLine, subtreeText)) }
        }

        private fun packChildren(
            parent: Node,
            maxSectionChars: Int,
            maxSiblingSubtreesPerSection: Int,
        ): List<MarkdownDocumentSection> {
            val sections = mutableListOf<MarkdownDocumentSection>()
            val buffer = mutableListOf<Node>()

            fun flushBuffer() {
                if (buffer.isEmpty()) return
                sections += buffer.toPackedSection(parent)
                buffer.clear()
            }

            parent.children.forEach { child ->
                val childText = text(child.startLine, child.endLine)
                if (childText.length > maxSectionChars) {
                    flushBuffer()
                    sections += packNode(child, maxSectionChars, maxSiblingSubtreesPerSection)
                    return@forEach
                }

                if (buffer.size >= maxSiblingSubtreesPerSection) {
                    flushBuffer()
                }

                val candidate = buffer + child
                val candidateText = text(candidate.first().startLine, candidate.last().endLine)
                if (candidateText.length > maxSectionChars && buffer.isNotEmpty()) {
                    flushBuffer()
                }
                buffer += child
            }
            flushBuffer()

            return sections
        }

        private fun List<Node>.toPackedSection(parent: Node): MarkdownDocumentSection {
            val first = first()
            val last = last()
            val headingPath = when (size) {
                1 -> first.headingPath()
                else -> parent.headingPath() + "${first.title} .. ${last.title}"
            }
            return MarkdownDocumentSection(
                index = 0,
                headingPath = headingPath,
                startLine = first.startLine,
                endLine = last.endLine,
                text = text(first.startLine, last.endLine),
            )
        }

        private fun Node.ownSection(): MarkdownDocumentSection? {
            val start = ownStartLine ?: return null
            val end = ownEndLine ?: return null
            val ownText = text(start, end)
            if (ownText.isBlank()) return null
            if (level > 0 && ownText.lines().drop(1).all { it.isBlank() }) return null
            return toSection(start, end, ownText)
        }

        private fun Node.toSection(
            startLine: Int,
            endLine: Int,
            text: String,
        ): MarkdownDocumentSection =
            MarkdownDocumentSection(
                index = 0,
                headingPath = headingPath(),
                startLine = startLine,
                endLine = endLine,
                text = text,
            )

        private fun Node.headingPath(): List<String> =
            ancestors().mapNotNull { it.title }

        private fun Node.ancestors(): List<Node> =
            generateSequence(this) { it.parent }
                .toList()
                .asReversed()
                .filter { it.level > 0 }

        private fun text(startLine: Int, endLine: Int): String {
            if (endLine < startLine) return ""
            return lines.subList(startLine - 1, endLine).joinToString("\n").trim()
        }

        companion object {
            fun build(lines: List<String>): MarkdownTree {
                val root = Node(
                    level = 0,
                    title = null,
                    startLine = 1,
                    parent = null,
                )
                val stack = mutableListOf(root)

                lines.forEachIndexed { index, line ->
                    val lineNumber = index + 1
                    val match = headingRegex.matchEntire(line) ?: return@forEachIndexed
                    val level = match.groupValues[1].length
                    val title = match.groupValues[2].trim()
                    while (stack.last().level >= level) {
                        stack.removeAt(stack.lastIndex)
                    }
                    val parent = stack.last()
                    val node = Node(
                        level = level,
                        title = title,
                        startLine = lineNumber,
                        parent = parent,
                    )
                    parent.children += node
                    stack += node
                }

                root.closeSubtree(lines.size.coerceAtLeast(1))
                return MarkdownTree(lines = lines, root = root)
            }
        }
    }

    private data class Node(
        val level: Int,
        val title: String?,
        val startLine: Int,
        val parent: Node?,
        val children: MutableList<Node> = mutableListOf(),
        var endLine: Int = startLine,
    ) {
        val ownStartLine: Int?
            get() = when {
                level == 0 && children.isEmpty() -> startLine
                level == 0 -> if (children.first().startLine > 1) 1 else null
                else -> startLine
            }

        val ownEndLine: Int?
            get() = when {
                children.isEmpty() -> endLine
                level == 0 -> children.first().startLine - 1
                else -> children.first().startLine - 1
            }.takeIf { ownStartLine != null && it >= ownStartLine!! }

        fun closeSubtree(documentEndLine: Int): Int {
            children.forEachIndexed { index, child ->
                val nextSiblingStart = children.getOrNull(index + 1)?.startLine
                val boundaryEnd = nextSiblingStart?.minus(1) ?: documentEndLine
                child.endLine = child.closeSubtree(boundaryEnd)
            }
            endLine = documentEndLine
            return documentEndLine
        }
    }
}

internal fun MarkdownDocumentSection.toMemorySourceText(
    title: String?,
    sourceRef: String,
    importedAt: Instant? = null,
): String = buildString {
    title?.takeIf { it.isNotBlank() }?.let { appendLine("Document title: $it") }
    appendLine("Document source: $sourceRef")
    importedAt?.let { appendLine("Document imported at: $it") }
    appendLine("Document section: $headingLabel")
    appendLine("Document lines: $startLine-$endLine")
    appendLine()
    append(text)
}

internal fun List<MarkdownDocumentSection>.toSectionSummaryJson() =
    buildJsonArray {
        val sectionCount = this@toSectionSummaryJson.size
        take(24).forEach { section ->
            add(
                buildJsonObject {
                    put("index", section.index)
                    put("heading", section.headingLabel)
                    put("start_line", section.startLine)
                    put("end_line", section.endLine)
                    put("chars", section.text.length)
                }
            )
        }
        if (sectionCount > 24) {
            add(JsonPrimitive("... ${sectionCount - 24} more sections"))
        }
    }

private fun String.sha256ForMarkdownImport(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
