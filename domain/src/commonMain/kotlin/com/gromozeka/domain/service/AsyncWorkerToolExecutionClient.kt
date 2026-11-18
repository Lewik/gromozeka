package com.gromozeka.domain.service

import com.gromozeka.domain.model.AsyncToolOperation

/** Existing WorkerRequest transport with a stable child ID and a Server-private parent fence. */
interface AsyncWorkerToolExecutionClient {
    fun captureTarget(target: ConversationRuntimeTaskTarget.Worker): ConversationRuntimeTaskTarget.Worker
    suspend fun submit(operation: AsyncToolOperation, resolvedSecretsByToolCallId: Map<String, Map<String, String>>)
    suspend fun cancel(operation: AsyncToolOperation)
    suspend fun reconcile(operation: AsyncToolOperation)
}
