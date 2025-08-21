package com.gromozeka.bot.utils

import com.gromozeka.shared.domain.message.MessageTagDefinition

tailrec fun String.parseInstructionTags(
    extractedTags: List<MessageTagDefinition.Data> = emptyList(),
): Pair<String, List<MessageTagDefinition.Data>> {
    val text = this.trim()
    if (!text.startsWith("<instruction>")) {
        return text to extractedTags
    }
    val (tag, newText) = text.split("</instruction>", limit = 2).map { it.trim() }
    val parts = tag.removePrefix("<instruction>").split(":", limit = 3)
    if (parts.size != 3) {
        return text to extractedTags
    }
    val (tagId, tagTitle, tagInstruction) = parts
    val tagData = MessageTagDefinition.Data(tagId.trim(), tagTitle.trim(), tagInstruction.trim())

    return newText.parseInstructionTags(extractedTags + tagData)
}