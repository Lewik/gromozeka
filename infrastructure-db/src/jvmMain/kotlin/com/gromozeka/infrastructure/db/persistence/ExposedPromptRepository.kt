package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.repository.PromptRepository
import com.gromozeka.infrastructure.db.persistence.tables.Prompts
import com.gromozeka.shared.path.KPath
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Service

@Service
class ExposedPromptRepository(
    private val fileSystemPromptScanner: FileSystemPromptScanner,
    private val builtinPromptLoader: BuiltinPromptLoader
) : PromptRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private var cachedFilePrompts: List<Prompt> = emptyList()
    private var cachedBuiltinPrompts: List<Prompt> = emptyList()

    override suspend fun findById(id: Prompt.Id): Prompt? {
        // Check DB first
        val dbPrompt = dbQuery {
            Prompts.selectAll().where { Prompts.id eq id.value }
                .map { it.toPrompt() }
                .singleOrNull()
        }
        
        if (dbPrompt != null) return dbPrompt
        
        // Check builtin prompts
        cachedBuiltinPrompts.find { it.id == id }?.let { return it }
        
        // Check file-based prompts
        return cachedFilePrompts.find { it.id == id }
    }

    override suspend fun findAll(): List<Prompt> {
        val dbPrompts = dbQuery {
            Prompts.selectAll()
                .orderBy(Prompts.name, SortOrder.ASC)
                .map { it.toPrompt() }
        }
        
        // Combine Builtin + User files + DB (inline)
        return (cachedBuiltinPrompts + cachedFilePrompts + dbPrompts).sortedBy { it.name }
    }

    override suspend fun findBySourceType(sourceType: Class<out Prompt.Source>): List<Prompt> = dbQuery {
        val sourceTypeName = when {
            sourceType.isAssignableFrom(Prompt.Source.Builtin::class.java) -> "BUILTIN"
            sourceType.isAssignableFrom(Prompt.Source.LocalFile.User::class.java) -> "USER_FILE"
            sourceType.isAssignableFrom(Prompt.Source.LocalFile.Imported::class.java) -> "IMPORTED"
            sourceType.isAssignableFrom(Prompt.Source.Remote.Url::class.java) -> "REMOTE_URL"
            sourceType.isAssignableFrom(Prompt.Source.Dynamic.Environment::class.java) -> "DYNAMIC_ENVIRONMENT"
            else -> "INLINE"
        }

        Prompts.selectAll().where { Prompts.sourceType eq sourceTypeName }
            .orderBy(Prompts.name, SortOrder.ASC)
            .map { it.toPrompt() }
    }

    override suspend fun refresh() {
        // Re-scan builtin and file-based prompts
        cachedBuiltinPrompts = builtinPromptLoader.loadBuiltinPrompts()
        cachedFilePrompts = fileSystemPromptScanner.scanUserPrompts()
    }

    override suspend fun count(): Int {
        val dbCount = dbQuery {
            Prompts.selectAll().count().toInt()
        }
        return cachedBuiltinPrompts.size + cachedFilePrompts.size + dbCount
    }
    
    override suspend fun save(prompt: Prompt): Prompt = dbQuery {
        val sourceTypeName = when (prompt.source) {
            is Prompt.Source.Builtin -> "BUILTIN"
            is Prompt.Source.LocalFile.User -> "USER_FILE"
            is Prompt.Source.LocalFile.Imported -> "IMPORTED"
            is Prompt.Source.Remote.Url -> "REMOTE_URL"
            is Prompt.Source.Dynamic.Environment -> "DYNAMIC_ENVIRONMENT"
            is Prompt.Source.Text -> "INLINE"
        }
        
        val sourcePath = when (val source = prompt.source) {
            is Prompt.Source.Builtin -> source.resourcePath.value
            is Prompt.Source.LocalFile -> source.path.value
            is Prompt.Source.Remote.Url -> source.url
            is Prompt.Source.Dynamic.Environment -> null
            is Prompt.Source.Text -> null
        }
        
        Prompts.insert {
            it[id] = prompt.id.value
            it[name] = prompt.name
            it[content] = prompt.content
            it[sourceType] = sourceTypeName
            it[this.sourcePath] = sourcePath
            it[createdAt] = prompt.createdAt.toKotlin()
            it[updatedAt] = prompt.updatedAt.toKotlin()
        }
        
        prompt
    }
    
    @jakarta.annotation.PostConstruct
    fun initialize() {
        // Load builtin and file-based prompts on startup
        cachedBuiltinPrompts = builtinPromptLoader.loadBuiltinPrompts()
        cachedFilePrompts = fileSystemPromptScanner.scanUserPrompts()
    }

    private fun ResultRow.toPrompt(): Prompt {
        val sourceType = this[Prompts.sourceType]
        val sourcePath = this[Prompts.sourcePath]

        val source: Prompt.Source = when (sourceType) {
            "BUILTIN" -> Prompt.Source.Builtin(KPath(sourcePath ?: ""))
            "USER_FILE" -> Prompt.Source.LocalFile.User(KPath(sourcePath ?: ""))
            "IMPORTED" -> Prompt.Source.LocalFile.Imported(KPath(sourcePath ?: ""))
            "REMOTE_URL" -> Prompt.Source.Remote.Url(sourcePath ?: "")
            "DYNAMIC_ENVIRONMENT" -> Prompt.Source.Dynamic.Environment
            "CLAUDE_GLOBAL", "CLAUDE_PROJECT" -> Prompt.Source.LocalFile.Imported(KPath(sourcePath ?: ""))
            else -> Prompt.Source.Builtin(KPath("inline"))
        }

        return Prompt(
            id = Prompt.Id(this[Prompts.id]),
            name = this[Prompts.name],
            content = this[Prompts.content],
            source = source,
            createdAt = this[Prompts.createdAt].toKotlinx(),
            updatedAt = this[Prompts.updatedAt].toKotlinx()
        )
    }
}
