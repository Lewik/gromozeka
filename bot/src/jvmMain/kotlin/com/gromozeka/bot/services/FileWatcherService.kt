package com.gromozeka.bot.services

import com.gromozeka.bot.utils.decodeProjectPath
import com.gromozeka.bot.utils.isSessionFile
import com.gromozeka.bot.utils.ClaudeCodePaths
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@Service
class FileWatcherService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchService: WatchService? = null
    private val _fileEvents = MutableSharedFlow<FileChangeEvent>()
    val fileEvents: Flow<FileChangeEvent> = _fileEvents
        .asSharedFlow()
        .debounce(300.milliseconds)

    fun startWatching() {
        scope.launch {
            val _watchService = registerDirectoriesRecursively(ClaudeCodePaths.PROJECTS_DIR)
            watchService = _watchService
            println("[FileWatcher] Started monitoring Claude Code sessions in: ${ClaudeCodePaths.PROJECTS_DIR.absolutePath}")

            processWatchEvents(_watchService)
        }
    }

    fun stopWatching() {
        watchService!!.close()
        scope.cancel()
        println("Stopped file monitoring")
    }

    private suspend fun registerDirectoriesRecursively(rootDir: File) = withContext(Dispatchers.IO) {
        val watchService = FileSystems.getDefault().newWatchService()

        Files.walk(rootDir.toPath()).use { paths ->
            paths.filter { Files.isDirectory(it) }
                .forEach { dir ->
                    try {
                        dir.register(
                            watchService,
                            ENTRY_CREATE,
                            ENTRY_MODIFY,
                            ENTRY_DELETE
                        )
                        println("[FileWatcher] Registered directory for monitoring: $dir")
                    } catch (e: Exception) {
                        println("[FileWatcher] Failed to register directory $dir: ${e.message}")
                    }
                }
        }
        return@withContext watchService
    }

    private suspend fun processWatchEvents(watchService: WatchService) {
        println("[FileWatcher] Starting watch event processing loop")
        while (scope.isActive) {
            try {
                val key = withContext(Dispatchers.IO) {
                    watchService.poll(1, TimeUnit.SECONDS)
                } ?: continue

                for (event in key.pollEvents()) {
                    val kind = event.kind()
                    val filename = event.context() as? Path

                    if (filename != null && filename.isSessionFile()) {
                        val watchable = key.watchable() as Path
                        val fullPath = watchable.resolve(filename)

                        val changeEvent = FileChangeEvent(
                            type = when (kind) {
                                ENTRY_CREATE -> FileChangeType.CREATED
                                ENTRY_MODIFY -> FileChangeType.MODIFIED
                                ENTRY_DELETE -> FileChangeType.DELETED
                                else -> continue
                            },
                            file = fullPath.toFile()
                        )

                        println("[FileWatcher] File event: ${changeEvent.type} - ${changeEvent.file.name} in ${changeEvent.file.parentFile.decodeProjectPath()}")
                        println("[FileWatcher] Emitting file event to flow...")
                        _fileEvents.emit(changeEvent)
                        println("[FileWatcher] File event emitted successfully")
                    }
                }

                if (!key.reset()) {
                    println("[FileWatcher] Watch key no longer valid")
                    break
                }

            } catch (e: Exception) {
                if (e is CancellationException) {
                    println("[FileWatcher] File watcher cancelled")
                    break
                } else {
                    println("[FileWatcher] Error processing watch events: ${e.message}")
                    delay(1000) // Wait before retrying
                }
            }
        }
    }

    private fun Path.isSessionFile() = toFile().isSessionFile()
}

data class FileChangeEvent(
    val type: FileChangeType,
    val file: File,
)

enum class FileChangeType {
    CREATED,
    MODIFIED,
    DELETED
}