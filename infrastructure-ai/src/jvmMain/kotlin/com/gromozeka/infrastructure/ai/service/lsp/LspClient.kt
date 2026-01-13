package com.gromozeka.infrastructure.ai.service.lsp

import klog.KLoggers
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * LSP client wrapper managing connection to a language server.
 *
 * Handles:
 * - Server process lifecycle
 * - LSP protocol initialization
 * - Request/response communication
 * - Diagnostics collection
 * - Graceful shutdown
 */
class LspClient(
    private val serverCommand: List<String>,
    private val rootUri: Path,
    private val language: String
) : AutoCloseable {

    private val log = KLoggers.logger(this)

    private val process: Process
    private val server: LanguageServer
    private val client: LanguageClientImpl

    @Volatile
    private var isInitialized = false

    init {
        log.info { "Starting LSP server for $language: ${serverCommand.joinToString(" ")}" }

        process = ProcessBuilder(serverCommand)
            .redirectError(ProcessBuilder.Redirect.INHERIT) // Don't block on stderr
            .apply {
                // Increase heap size for kotlin-language-server
                if (language.lowercase() == "kotlin") {
                    environment()["KOTLIN_LANGUAGE_SERVER_OPTS"] = "-Xmx8g -Xms2g"
                    log.info { "Set kotlin-language-server heap: 8GB max, 2GB initial" }
                }
            }
            .start()

        client = LanguageClientImpl()
        val launcher = LSPLauncher.createClientLauncher(
            client,
            process.inputStream,
            process.outputStream
        )

        server = launcher.remoteProxy
        launcher.startListening()

        initialize()
    }

    private fun initialize() {
        val initParams = InitializeParams().apply {
            processId = ProcessHandle.current().pid().toInt()
            rootUri = this@LspClient.rootUri.resolve("domain").toUri().toString()
            log.info { "LSP rootUri set to domain subdirectory: $rootUri" }

            capabilities = ClientCapabilities().apply {
                textDocument = TextDocumentClientCapabilities().apply {
                    definition = DefinitionCapabilities()
                    hover = HoverCapabilities()
                    publishDiagnostics = PublishDiagnosticsCapabilities()
                    documentSymbol = DocumentSymbolCapabilities()
                    references = ReferencesCapabilities()
                }
            }
        }

        try {
            val result = server.initialize(initParams).get(30, TimeUnit.SECONDS)
            log.info { "LSP server initialized for $language: ${result.serverInfo?.name ?: "unknown"}" }

            server.initialized(InitializedParams())
            isInitialized = true
        } catch (e: Exception) {
            log.error(e) { "Failed to initialize LSP server for $language" }
            process.destroy()
            throw e
        }
    }

    /**
     * Find definition of symbol at position.
     */
    fun findDefinition(filePath: String, line: Int, column: Int): List<LocationInfo> {
        ensureInitialized()

        val params = DefinitionParams(
            TextDocumentIdentifier(Path.of(filePath).toUri().toString()),
            Position(line, column)
        )

        return try {
            val result = server.textDocumentService
                .definition(params)
                .get(10, TimeUnit.SECONDS)

            when {
                result == null -> emptyList()
                result.isLeft -> result.left.map { it.toLocationInfo() }
                result.isRight -> result.right.map { it.toLocationInfo() }
                else -> emptyList()
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to find definition at $filePath:$line:$column" }
            emptyList()
        }
    }

    /**
     * Find all references to symbol at position.
     */
    fun findReferences(
        filePath: String,
        line: Int,
        column: Int,
        includeDeclaration: Boolean
    ): List<LocationInfo> {
        ensureInitialized()

        val params = ReferenceParams(
            TextDocumentIdentifier(Path.of(filePath).toUri().toString()),
            Position(line, column),
            ReferenceContext(includeDeclaration)
        )

        return try {
            val result = server.textDocumentService
                .references(params)
                .get(30, TimeUnit.SECONDS)

            result?.map { it.toLocationInfo() } ?: emptyList()
        } catch (e: Exception) {
            log.error(e) { "Failed to find references at $filePath:$line:$column" }
            emptyList()
        }
    }

    /**
     * Get hover information for symbol at position.
     */
    fun getHover(filePath: String, line: Int, column: Int): HoverInfo? {
        ensureInitialized()

        val params = HoverParams(
            TextDocumentIdentifier(Path.of(filePath).toUri().toString()),
            Position(line, column)
        )

        return try {
            val result = server.textDocumentService
                .hover(params)
                .get(10, TimeUnit.SECONDS)

            result?.let {
                HoverInfo(
                    content = it.contents.right?.value ?: it.contents.left?.joinToString("\n") { item ->
                        when {
                            item.isLeft -> item.left
                            item.isRight -> item.right.value
                            else -> ""
                        }
                    } ?: "",
                    range = it.range?.let { r ->
                        RangeInfo(
                            start = PositionInfo(r.start.line, r.start.character),
                            end = PositionInfo(r.end.line, r.end.character)
                        )
                    }
                )
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to get hover info at $filePath:$line:$column" }
            null
        }
    }

    /**
     * Get diagnostics for file.
     */
    fun getDiagnostics(filePath: String): List<DiagnosticInfo> {
        val uri = Path.of(filePath).toUri().toString()
        return client.getDiagnostics(uri)
    }

    /**
     * Get document symbols (hierarchical symbol structure).
     */
    fun getDocumentSymbols(filePath: String): List<org.eclipse.lsp4j.DocumentSymbol> {
        ensureInitialized()

        val params = org.eclipse.lsp4j.DocumentSymbolParams(
            TextDocumentIdentifier(Path.of(filePath).toUri().toString())
        )

        return try {
            val result = server.textDocumentService
                .documentSymbol(params)
                .get(60, TimeUnit.SECONDS)

            result?.mapNotNull { either ->
                when {
                    either.isLeft -> {
                        // Old format: SymbolInformation (flat) - convert to DocumentSymbol
                        either.left.toDocumentSymbol()
                    }
                    either.isRight -> {
                        // New format: DocumentSymbol (hierarchical)
                        either.right
                    }
                    else -> null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            log.error(e) { "Failed to get document symbols for $filePath" }
            emptyList()
        }
    }

    /**
     * Notify server about file open.
     */
    fun didOpenFile(filePath: String, content: String) {
        val item = TextDocumentItem(
            Path.of(filePath).toUri().toString(),
            language,
            1,
            content
        )

        server.textDocumentService.didOpen(DidOpenTextDocumentParams(item))
    }

    /**
     * Notify server about file change.
     */
    fun didChangeFile(filePath: String, content: String, version: Int) {
        val changes = listOf(
            TextDocumentContentChangeEvent(content)
        )

        val params = DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(
                Path.of(filePath).toUri().toString(),
                version
            ),
            changes
        )

        server.textDocumentService.didChange(params)
    }

    /**
     * Notify server about file close.
     */
    fun didCloseFile(filePath: String) {
        server.textDocumentService.didClose(
            DidCloseTextDocumentParams(
                TextDocumentIdentifier(Path.of(filePath).toUri().toString())
            )
        )
    }

    private fun ensureInitialized() {
        if (!isInitialized) {
            error("LSP client not initialized")
        }
    }

    override fun close() {
        try {
            if (isInitialized) {
                server.shutdown().get(5, TimeUnit.SECONDS)
                server.exit()
            }
        } catch (e: Exception) {
            log.warn(e) { "Error during LSP server shutdown" }
        } finally {
            process.destroy()
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
}

/**
 * Implementation of LanguageClient that collects diagnostics.
 */
private class LanguageClientImpl : LanguageClient {
    private val diagnostics = mutableMapOf<String, List<DiagnosticInfo>>()
    private val log = KLoggers.logger(this)

    override fun telemetryEvent(`object`: Any?) {
        log.debug { "LSP telemetry: $`object`" }
    }

    override fun publishDiagnostics(diagnosticsParams: PublishDiagnosticsParams?) {
        if (diagnosticsParams != null) {
            val uri = diagnosticsParams.uri
            val diags = diagnosticsParams.diagnostics.map { it.toDiagnosticInfo() }
            diagnostics[uri] = diags

            log.debug { "Received ${diags.size} diagnostics for $uri" }
        }
    }

    override fun showMessage(messageParams: MessageParams?) {
        log.info { "LSP message: ${messageParams?.message}" }
    }

    override fun showMessageRequest(requestParams: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> {
        return CompletableFuture.completedFuture(null)
    }

    override fun logMessage(messageParams: MessageParams?) {
        val level = messageParams?.type
        val message = messageParams?.message ?: ""

        when (level) {
            MessageType.Error -> log.error { "LSP: $message" }
            MessageType.Warning -> log.warn { "LSP: $message" }
            MessageType.Info -> log.info { "LSP: $message" }
            MessageType.Log -> log.debug { "LSP: $message" }
            else -> log.debug { "LSP: $message" }
        }
    }

    fun getDiagnostics(uri: String): List<DiagnosticInfo> {
        return diagnostics[uri] ?: emptyList()
    }
}

/**
 * Location information.
 */
data class LocationInfo(
    val uri: String,
    val range: RangeInfo
)

data class RangeInfo(
    val start: PositionInfo,
    val end: PositionInfo
)

data class PositionInfo(
    val line: Int,
    val column: Int
)

/**
 * Hover information.
 */
data class HoverInfo(
    val content: String,
    val range: RangeInfo?
)

/**
 * Diagnostic information.
 */
data class DiagnosticInfo(
    val severity: String,
    val message: String,
    val range: RangeInfo,
    val code: String?,
    val source: String?
)

// Extension functions for conversion

private fun Location.toLocationInfo() = LocationInfo(
    uri = uri,
    range = range.toRangeInfo()
)

private fun LocationLink.toLocationInfo() = LocationInfo(
    uri = targetUri,
    range = targetRange.toRangeInfo()
)

private fun Range.toRangeInfo() = RangeInfo(
    start = PositionInfo(start.line, start.character),
    end = PositionInfo(end.line, end.character)
)

private fun Diagnostic.toDiagnosticInfo() = DiagnosticInfo(
    severity = when (severity) {
        DiagnosticSeverity.Error -> "error"
        DiagnosticSeverity.Warning -> "warning"
        DiagnosticSeverity.Information -> "info"
        DiagnosticSeverity.Hint -> "hint"
        else -> "unknown"
    },
    message = message,
    range = range.toRangeInfo(),
    code = code?.left,
    source = source
)

private fun org.eclipse.lsp4j.SymbolInformation.toDocumentSymbol() = org.eclipse.lsp4j.DocumentSymbol(
    name,
    kind,
    location.range,
    location.range,
    null,
    null
)
