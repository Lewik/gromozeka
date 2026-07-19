package com.gromozeka.domain.model.treesitter

import kotlinx.serialization.Serializable

@Serializable
data class AstNode(
    val type: String,
    val startLine: Int,
    val endLine: Int,
    val startColumn: Int,
    val endColumn: Int,
    val text: String? = null,
    val children: List<AstNode> = emptyList(),
    val fieldName: String? = null
)

@Serializable
data class FileAst(
    val file: String,
    val language: String,
    val tree: AstNode
)
