package com.gromozeka.infrastructure.ai.treesitter

import com.gromozeka.domain.model.treesitter.AstNode
import com.gromozeka.domain.model.treesitter.FileAst
import com.gromozeka.domain.service.treesitter.TreeSitterService
import io.github.treesitter.ktreesitter.Node
import org.springframework.stereotype.Service
import java.nio.file.Files
import kotlin.math.min

/**
 * Implementation of TreeSitterService
 */
@Service
class TreeSitterServiceImpl(
    private val projectRegistry: ProjectRegistryImpl,
    private val languageRegistry: LanguageRegistryImpl
) : TreeSitterService {
    
    override suspend fun getAst(
        projectName: String,
        filePath: String,
        maxDepth: Int,
        includeText: Boolean
    ): FileAst {
        // Get file path
        val absolutePath = projectRegistry.getFilePath(projectName, filePath)
        
        // Check if file exists
        if (!Files.exists(absolutePath)) {
            throw IllegalArgumentException("File not found: $filePath")
        }
        
        // Detect language
        val language = languageRegistry.languageForFile(filePath)
            ?: throw IllegalArgumentException("Could not detect language for file: $filePath")
        
        // Check if language is supported
        if (!languageRegistry.isLanguageAvailable(language)) {
            throw IllegalArgumentException("Language '$language' is not supported")
        }
        
        // Parse file
        val sourceBytes = Files.readAllBytes(absolutePath)
        val sourceString = String(sourceBytes, Charsets.UTF_8)
        val parser = languageRegistry.getParser(language)
        val tree = parser.parse(sourceString)
        
        // Convert to AST representation
        val astNode = nodeToAst(tree.rootNode, sourceBytes, includeText, maxDepth)
        
        return FileAst(
            file = filePath,
            language = language,
            tree = astNode
        )
    }
    
    private fun nodeToAst(
        node: Node,
        sourceBytes: ByteArray,
        includeText: Boolean,
        maxDepth: Int,
        currentDepth: Int = 0
    ): AstNode {
        val text = if (includeText) {
            try {
                val startByte = node.startByte.toInt()
                val endByte = node.endByte.toInt()
                String(sourceBytes.sliceArray(startByte until min(endByte, sourceBytes.size)))
            } catch (e: Exception) {
                null
            }
        } else null
        
        val children = if (currentDepth < maxDepth && node.childCount > 0u) {
            (0u until node.childCount).map { i ->
                nodeToAst(node.child(i)!!, sourceBytes, includeText, maxDepth, currentDepth + 1)
            }
        } else {
            emptyList()
        }
        
        return AstNode(
            type = node.type,
            startLine = node.startPoint.row.toInt(),
            endLine = node.endPoint.row.toInt(),
            startColumn = node.startPoint.column.toInt(),
            endColumn = node.endPoint.column.toInt(),
            text = text,
            children = children,
            fieldName = null // Tree-sitter Kotlin SDK doesn't expose field names easily
        )
    }
}
