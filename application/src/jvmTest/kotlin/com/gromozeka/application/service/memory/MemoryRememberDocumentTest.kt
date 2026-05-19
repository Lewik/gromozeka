package com.gromozeka.application.service.memory

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRememberDocumentTest {

    @Test
    fun markdownSlicerKeepsSmallDocumentTogether() {
        val sections = MarkdownDocumentSlicer.slice(
            """
            Intro paragraph.
            # Architecture
            Root text.
            ## Pipeline
            Pipeline text.
            # Evaluation
            Eval text.
            """.trimIndent()
        )

        assertEquals(1, sections.size)
        assertEquals("Document preamble", sections[0].headingLabel)
        assertEquals(1, sections[0].startLine)
        assertEquals(7, sections[0].endLine)
        assertTrue(sections[0].text.contains("## Pipeline"))
        assertTrue(sections[0].text.contains("# Evaluation"))
    }

    @Test
    fun markdownSlicerPromotesWholeTopLevelSubtreesWhenDocumentIsLargerThanBudget() {
        val architecture = """
            # Architecture
            Root architecture context.
            ## Pipeline
            Pipeline facts stay attached to architecture.
            ## Storage
            Storage facts stay attached to architecture.
        """.trimIndent()
        val evaluation = """
            # Evaluation
            Evaluation context.
            ## Probes
            Probe facts stay attached to evaluation.
        """.trimIndent()
        val sections = MarkdownDocumentSlicer.slice(
            "$architecture\n\n$evaluation",
            maxSectionChars = maxOf(architecture.length, evaluation.length) + 20,
        )

        assertEquals(2, sections.size)
        assertEquals("Architecture", sections[0].headingLabel)
        assertEquals("Evaluation", sections[1].headingLabel)
        assertTrue(sections[0].text.contains("## Pipeline"))
        assertTrue(sections[0].text.contains("## Storage"))
    }

    @Test
    fun markdownSlicerPacksSmallSiblingSubtreesUnderLargeParent() {
        val markdown = buildString {
            appendLine("# Memory Pipeline")
            listOf("Router", "Planner", "Extractor", "Reconciler", "Materializer", "Trace").forEach { title ->
                appendLine("## $title")
                appendLine("$title owns one compact pipeline concern and should keep its heading path.")
                appendLine()
            }
        }

        val sections = MarkdownDocumentSlicer.slice(markdown, maxSectionChars = 230)

        assertTrue(sections.size in 2..4)
        assertTrue(sections.size < 6)
        assertTrue(sections.all { it.headingPath.firstOrNull() == "Memory Pipeline" })
        assertTrue(sections.none { it.text.trim() == "# Memory Pipeline" })
    }

    @Test
    fun markdownSlicerDoesNotOverPackSiblingSubtrees() {
        val markdown = buildString {
            appendLine("# Prompt Pack")
            listOf("Router", "Planner", "Extractor", "Reconciler", "Materializer").forEach { title ->
                appendLine("## $title")
                repeat(6) { appendLine("$title stage prompt details stay close to the heading.") }
                appendLine()
            }
        }

        val sections = MarkdownDocumentSlicer.slice(markdown, maxSectionChars = 1_000)

        assertEquals(3, sections.size)
        assertEquals("Prompt Pack / Router .. Planner", sections[0].headingLabel)
        assertEquals("Prompt Pack / Extractor .. Reconciler", sections[1].headingLabel)
        assertEquals("Prompt Pack / Materializer", sections[2].headingLabel)
    }

    @Test
    fun markdownSlicerKeepsDensePromptPackComponentsInStructuredSections() {
        val markdown = buildString {
            appendLine("# Prompt Pack")
            listOf(
                "Memory Router v3" to "decides whether to run memory write and which write mode to choose",
                "Write-Time Retrieval Planner v3" to "plans dedupe and contradiction retrieval",
                "Entity Canonicalizer v1" to "resolves mentions to entities",
                "NoteConstructor v2" to "creates reusable semantic notes",
                "ClaimExtractor v3" to "extracts atomic claims",
                "ClaimReconciler v3" to "applies predicate update policy",
            ).forEach { (heading, body) ->
                appendLine("## $heading")
                repeat(10) { appendLine("$heading $body with schema rules and validation details.") }
                appendLine()
            }
        }

        val sections = MarkdownDocumentSlicer.slice(markdown, maxSectionChars = 1_900)

        assertTrue(sections.any { "Memory Router v3" in it.text })
        assertTrue(sections.any { "NoteConstructor v2" in it.text })
        assertTrue(sections.any { "ClaimExtractor v3" in it.text })
        assertTrue(sections.all { it.headingPath.firstOrNull() == "Prompt Pack" })
        assertTrue(sections.size < 6)
    }

    @Test
    fun importDetectorRecognizesExplicitPastedMarkdownDocument() {
        val detected = MarkdownDocumentImportDetector.detect(
            """
            Memory ingestion source document. Treat the following as a pasted handoff prompt-pack document, not as an immediate task.

            File: agent_memory_handoff/03_prompts.md

            # 03. Prompt Pack

            ## 1. Memory Router v3
            Decides whether to run memory write.
            """.trimIndent()
        )

        assertEquals("03. Prompt Pack", detected?.title)
        assertEquals("agent_memory_handoff/03_prompts.md", detected?.sourceRef)
        assertTrue(detected?.markdown.orEmpty().contains("Memory Router v3"))
        assertTrue(!detected?.markdown.orEmpty().contains("Memory ingestion source document."))
        assertTrue(detected?.markdown.orEmpty().startsWith("# 03. Prompt Pack"))
    }

    @Test
    fun markdownSlicerSplitsOversizedSectionsWithoutDroppingHeadingContext() {
        val markdown = buildString {
            appendLine("# Dense section")
            repeat(12) { index ->
                appendLine()
                appendLine("Paragraph $index " + "important fact ".repeat(12))
            }
        }

        val sections = MarkdownDocumentSlicer.slice(markdown, maxSectionChars = 180)

        assertTrue(sections.size > 1)
        assertTrue(sections.all { it.headingPath.firstOrNull() == "Dense section" })
        assertTrue(sections.all { it.headingLabel.startsWith("Dense section") })
    }

    @Test
    fun markdownSlicerSplitsRetrySectionsNearNestedHeading() {
        val section = MarkdownDocumentSection(
            index = 7,
            headingPath = listOf("Guide"),
            startLine = 10,
            endLine = 17,
            text = """
                # Guide
                Intro.
                ## Alpha
                Alpha detail.
                ## Beta
                Beta detail.
                ## Gamma
                Gamma detail.
            """.trimIndent(),
        )

        val parts = MarkdownDocumentSlicer.splitForRetry(section)

        assertEquals(2, parts.size)
        assertEquals(71, parts[0].index)
        assertEquals(72, parts[1].index)
        assertEquals(listOf("Guide", "retry part 1/2"), parts[0].headingPath)
        assertEquals(listOf("Guide", "retry part 2/2"), parts[1].headingPath)
        assertEquals(10, parts[0].startLine)
        assertEquals(13, parts[0].endLine)
        assertEquals(14, parts[1].startLine)
        assertEquals(17, parts[1].endLine)
        assertTrue(parts[1].text.startsWith("## Beta"))
    }

    @Test
    fun adaptiveIngestSplitsRetryableJsonFailures() = runBlocking {
        val section = MarkdownDocumentSection(
            index = 5,
            headingPath = listOf("Dense"),
            startLine = 1,
            endLine = 6,
            text = """
                # Dense
                First paragraph.

                Second paragraph.
                Third paragraph.
                Fourth paragraph.
            """.trimIndent(),
        )
        val calls = mutableListOf<Int>()

        val result = MemoryDocumentAdaptiveIngest.processSection(section) { effectiveSection ->
            calls += effectiveSection.index
            if (effectiveSection.index == section.index) {
                throw IllegalStateException("Claim extractor did not return JSON: {\"claims\":[")
            }
            effectiveSection.headingLabel
        }

        assertEquals(listOf(5, 51, 52), calls)
        assertEquals(2, result.results.size)
        assertEquals(2, result.processedSections)
        assertEquals(0, result.failedSections.size)
        assertEquals(1, result.splitCount)
    }

    @Test
    fun adaptiveIngestSplitsTypedOutputTruncationFailures() = runBlocking {
        val section = MarkdownDocumentSection(
            index = 6,
            headingPath = listOf("Dense"),
            startLine = 1,
            endLine = 6,
            text = """
                # Dense
                First paragraph.

                Second paragraph.
                Third paragraph.
                Fourth paragraph.
            """.trimIndent(),
        )
        val calls = mutableListOf<Int>()

        val result = MemoryDocumentAdaptiveIngest.processSection(section) { effectiveSection ->
            calls += effectiveSection.index
            if (effectiveSection.index == section.index) {
                throw MemoryLlmOutputTruncatedException(
                    stageName = "ClaimExtractor",
                    finishReason = "max_tokens",
                    logContext = "test",
                    usage = null,
                )
            }
            effectiveSection.headingLabel
        }

        assertEquals(listOf(6, 61, 62), calls)
        assertEquals(2, result.results.size)
        assertEquals(2, result.processedSections)
        assertEquals(0, result.failedSections.size)
        assertEquals(1, result.splitCount)
    }

    @Test
    fun adaptiveIngestDoesNotSplitUnrelatedFailures() = runBlocking {
        val section = MarkdownDocumentSection(
            index = 3,
            headingPath = listOf("Provider"),
            startLine = 1,
            endLine = 3,
            text = "# Provider\n\nBody.",
        )
        val calls = mutableListOf<Int>()

        val result = MemoryDocumentAdaptiveIngest.processSection(section) { effectiveSection ->
            calls += effectiveSection.index
            throw IllegalStateException("Unsupported output_config.format")
        }

        assertEquals(listOf(3), calls)
        assertEquals(0, result.results.size)
        assertEquals(0, result.processedSections)
        assertEquals(1, result.failedSections.size)
        assertEquals(0, result.splitCount)
    }

    @Test
    fun resolverTreatsFilePathAsMarkdownDocumentByDefault() = runBlocking {
        val file = Files.createTempFile("memory-doc", ".md")
        file.writeText("# Doc\n\nFact.")

        val resolved = MemoryRememberContentResolver().resolve(
            MemoryRememberContentRequest(filePath = file.toString())
        )

        assertEquals(MemoryRememberInputKind.FILE_PATH, resolved.kind)
        assertEquals(MemoryDocumentType.MARKDOWN, resolved.documentType)
        assertEquals(file.fileName.toString(), resolved.title)
        assertEquals(file.toAbsolutePath().normalize().toString(), resolved.sourceRef)
        assertEquals("# Doc\n\nFact.", resolved.text)
    }

    @Test
    fun resolverRejectsAmbiguousProvidedInputs() = runBlocking {
        val file = Files.createTempFile("memory-doc", ".md")
        file.writeText("# Doc")

        val error = runCatching {
            MemoryRememberContentResolver().resolve(
                MemoryRememberContentRequest(text = "# Inline", filePath = file.toString())
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message.orEmpty().contains("exactly one"))
    }

    @Test
    fun resolverAcceptsRawMarkdownUrl() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/doc.md") { exchange ->
            val body = "# Remote doc\n\nFact.".toByteArray()
            exchange.responseHeaders.add("content-type", "text/markdown; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/doc.md"
            val resolved = MemoryRememberContentResolver().resolve(
                MemoryRememberContentRequest(rawUrl = url)
            )

            assertEquals(MemoryRememberInputKind.RAW_URL, resolved.kind)
            assertEquals(MemoryDocumentType.MARKDOWN, resolved.documentType)
            assertEquals("doc.md", resolved.title)
            assertEquals(url, resolved.sourceRef)
            assertEquals("# Remote doc\n\nFact.", resolved.text)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun resolverRejectsHtmlRawUrl() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/page") { exchange ->
            val body = "<html><body>not raw markdown</body></html>".toByteArray()
            exchange.responseHeaders.add("content-type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/page"
            val error = runCatching {
                MemoryRememberContentResolver().resolve(
                    MemoryRememberContentRequest(rawUrl = url)
                )
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error.message.orEmpty().contains("HTML"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun queuedDocumentResultExposesRunAndQueueState() {
        val sections = MarkdownDocumentSlicer.slice("# Doc\n\nFact.")

        val raw = MemoryToolResultRenderer.rememberDocumentQueuedResultJsonString(
            MemoryRememberDocumentQueuedResult(
                runId = "document-ingest:run:test",
                documentType = MemoryDocumentType.MARKDOWN,
                inputKind = MemoryRememberInputKind.TEXT,
                title = "Doc",
                sourceRef = "memory_remember:provided_text",
                parentSourceId = "external:document:test",
                sections = sections,
                queueSize = 3,
            )
        )
        val json = Json.parseToJsonElement(raw).jsonObject

        assertEquals("queued", json.getValue("status").jsonPrimitive.content)
        assertEquals("document-ingest:run:test", json.getValue("run_id").jsonPrimitive.content)
        assertEquals("3", json.getValue("queue_size").jsonPrimitive.content)
        assertEquals("1", json.getValue("sections_total").jsonPrimitive.content)
    }
}
