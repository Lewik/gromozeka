package com.gromozeka.domain.model

fun Conversation.Message.assistantTextWithRuntimeObservations(text: String): String {
    val observations = instructions.filterIsInstance<Conversation.Message.Instruction.RuntimeObservation>()
    if (observations.isEmpty()) return text
    check(role == Conversation.Message.Role.ASSISTANT && author is Conversation.Message.Author.Runtime) {
        "Assistant runtime observations require Runtime authorship"
    }
    return (observations.map { it.toXmlLine() } + text).filter(String::isNotBlank).joinToString("\n")
}
