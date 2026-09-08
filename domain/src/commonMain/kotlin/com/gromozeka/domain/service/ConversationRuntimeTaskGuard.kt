package com.gromozeka.domain.service

fun interface ConversationRuntimeTaskGuard {
    suspend fun validate(task: ConversationRuntimeTask)
}
