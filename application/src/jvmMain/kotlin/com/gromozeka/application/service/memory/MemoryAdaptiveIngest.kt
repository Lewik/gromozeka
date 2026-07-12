package com.gromozeka.application.service.memory

import klog.KLoggers
import kotlinx.coroutines.CancellationException

internal object MemoryDocumentAdaptiveIngest {
    private val log = KLoggers.logger(this)
    const val DEFAULT_MAX_SPLIT_DEPTH = 4

    suspend fun <T> processSection(
        section: MarkdownDocumentSection,
        maxSplitDepth: Int = DEFAULT_MAX_SPLIT_DEPTH,
        failFastOnError: Boolean = false,
        processor: suspend (MarkdownDocumentSection) -> T,
    ): MemoryDocumentAdaptiveSectionResult<T> =
        processSection(
            section = section,
            maxSplitDepth = maxSplitDepth,
            failFastOnError = failFastOnError,
            depth = 0,
            processor = processor,
        )

    private suspend fun <T> processSection(
        section: MarkdownDocumentSection,
        maxSplitDepth: Int,
        failFastOnError: Boolean,
        depth: Int,
        processor: suspend (MarkdownDocumentSection) -> T,
    ): MemoryDocumentAdaptiveSectionResult<T> {
        return runCatching {
            processor(section)
        }.fold(
            onSuccess = { result ->
                MemoryDocumentAdaptiveSectionResult(
                    results = listOf(result),
                    processedSections = 1,
                    failedSections = emptyList(),
                    splitCount = 0,
                )
            },
            onFailure = { error ->
                if (error is CancellationException) {
                    throw error
                }

                val parts = if (depth < maxSplitDepth && error.isLikelyMemoryDocumentSplitCandidate()) {
                    MarkdownDocumentSlicer.splitForRetry(section)
                } else {
                    emptyList()
                }

                if (parts.size < 2) {
                    if (failFastOnError) {
                        throw error
                    }

                    return MemoryDocumentAdaptiveSectionResult(
                        results = emptyList(),
                        processedSections = 0,
                        failedSections = listOf(
                            MemoryDocumentAdaptiveSectionFailure(
                                section = section,
                                message = error.message ?: error::class.simpleName.orEmpty(),
                                error = error,
                            )
                        ),
                        splitCount = 0,
                    )
                }

                log.warn(error) {
                    "Memory document section adaptive split: section=${section.index} heading=${section.headingLabel} " +
                        "depth=$depth parts=${parts.size} chars=${section.text.length} error=${error.message}"
                }

                parts
                    .map { part ->
                        processSection(
                            section = part,
                            maxSplitDepth = maxSplitDepth,
                            failFastOnError = failFastOnError,
                            depth = depth + 1,
                            processor = processor,
                        )
                    }
                    .reduce { acc, next -> acc + next }
                    .let { result -> result.copy(splitCount = result.splitCount + 1) }
            },
        )
    }
}

internal data class MemoryDocumentAdaptiveSectionResult<T>(
    val results: List<T>,
    val processedSections: Int,
    val failedSections: List<MemoryDocumentAdaptiveSectionFailure>,
    val splitCount: Int,
) {
    val attemptedSections: Int
        get() = processedSections + failedSections.size

    operator fun plus(other: MemoryDocumentAdaptiveSectionResult<T>): MemoryDocumentAdaptiveSectionResult<T> =
        MemoryDocumentAdaptiveSectionResult(
            results = results + other.results,
            processedSections = processedSections + other.processedSections,
            failedSections = failedSections + other.failedSections,
            splitCount = splitCount + other.splitCount,
        )
}

internal data class MemoryDocumentAdaptiveSectionFailure(
    val section: MarkdownDocumentSection,
    val message: String,
    val error: Throwable,
)

internal fun Throwable.isLikelyMemoryDocumentSplitCandidate(): Boolean {
    if (this is MemoryLlmOutputTruncatedException) {
        return true
    }

    val chainText = generateSequence(this) { it.cause }
        .joinToString(" | ") { error ->
            "${error::class.qualifiedName.orEmpty()}: ${error.message.orEmpty()}"
        }
        .lowercase()

    return listOf(
        "did not return json",
        "jsondecodingexception",
        "serializationexception",
        "unexpected json token",
        "unexpected end",
        "end of input",
        "eof",
        "unterminated",
        "incomplete json",
        "truncated",
        "finish_reason",
        "finish reason",
        "max_tokens",
        "max tokens",
        "output limit",
        "maximum output",
        "response length",
        "content too long",
    ).any { marker -> chainText.contains(marker) }
}
