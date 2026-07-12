package com.gromozeka.application.service.memory

import klog.KLoggers
import kotlinx.coroutines.CancellationException

internal object MemoryAdaptiveIngest {
    private val log = KLoggers.logger(this)
    const val DEFAULT_MAX_SPLIT_DEPTH = 4

    suspend fun <T> processSection(
        section: MemoryIngestSection,
        maxSplitDepth: Int = DEFAULT_MAX_SPLIT_DEPTH,
        failFastOnError: Boolean = false,
        processor: suspend (MemoryIngestSection) -> T,
    ): MemoryAdaptiveSectionResult<T> =
        processSection(
            section = section,
            maxSplitDepth = maxSplitDepth,
            failFastOnError = failFastOnError,
            depth = 0,
            processor = processor,
        )

    private suspend fun <T> processSection(
        section: MemoryIngestSection,
        maxSplitDepth: Int,
        failFastOnError: Boolean,
        depth: Int,
        processor: suspend (MemoryIngestSection) -> T,
    ): MemoryAdaptiveSectionResult<T> {
        return runCatching {
            processor(section)
        }.fold(
            onSuccess = { result ->
                MemoryAdaptiveSectionResult(
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

                val parts = if (depth < maxSplitDepth && error.isLikelyMemoryIngestSplitCandidate()) {
                    MemoryIngestSectionSlicer.splitForRetry(section)
                } else {
                    emptyList()
                }

                if (parts.size < 2) {
                    if (failFastOnError) {
                        throw error
                    }

                    return MemoryAdaptiveSectionResult(
                        results = emptyList(),
                        processedSections = 0,
                        failedSections = listOf(
                            MemoryAdaptiveSectionFailure(
                                section = section,
                                message = error.message ?: error::class.simpleName.orEmpty(),
                                error = error,
                            )
                        ),
                        splitCount = 0,
                    )
                }

                log.warn(error) {
                    "Memory ingest section adaptive split: section=${section.index} heading=${section.headingLabel} " +
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

internal data class MemoryAdaptiveSectionResult<T>(
    val results: List<T>,
    val processedSections: Int,
    val failedSections: List<MemoryAdaptiveSectionFailure>,
    val splitCount: Int,
) {
    val attemptedSections: Int
        get() = processedSections + failedSections.size

    operator fun plus(other: MemoryAdaptiveSectionResult<T>): MemoryAdaptiveSectionResult<T> =
        MemoryAdaptiveSectionResult(
            results = results + other.results,
            processedSections = processedSections + other.processedSections,
            failedSections = failedSections + other.failedSections,
            splitCount = splitCount + other.splitCount,
        )
}

internal data class MemoryAdaptiveSectionFailure(
    val section: MemoryIngestSection,
    val message: String,
    val error: Throwable,
)

internal fun Throwable.isLikelyMemoryIngestSplitCandidate(): Boolean {
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
