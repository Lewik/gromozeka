package com.gromozeka.domain.service.treesitter

import com.gromozeka.domain.model.treesitter.FileAst

interface TreeSitterService {
    fun getAst(
        workspaceRootPath: String,
        filePath: String,
        maxDepth: Int = 5,
        includeText: Boolean = true,
    ): FileAst
}
