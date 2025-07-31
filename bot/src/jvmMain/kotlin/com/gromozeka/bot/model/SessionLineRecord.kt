package com.gromozeka.bot.model

data class SessionLineRecord(
    val fileName: String,
    val lineNumber: Int,
    val contentHash: String,
    val jsonContent: String,
    val parsedMessage: ChatMessage?
)

data class FileComparison(
    val newLines: List<SessionLineRecord>,
    val modifiedLines: List<SessionLineRecord>,
    val deletedLineNumbers: List<Int>
)

