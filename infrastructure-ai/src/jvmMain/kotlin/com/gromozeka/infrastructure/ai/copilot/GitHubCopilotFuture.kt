package com.gromozeka.infrastructure.ai.copilot

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

internal suspend fun <T> CompletableFuture<T>.awaitCancellable(
    cancelFutureOnCancellation: Boolean = true,
    onCancellation: () -> Unit = {},
): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (continuation.isActive) {
            continuation.resumeWith(
                if (error == null) {
                    Result.success(value)
                } else {
                    Result.failure(error.unwrapCompletionException())
                }
            )
        }
    }
    continuation.invokeOnCancellation {
        onCancellation()
        if (cancelFutureOnCancellation) {
            cancel(true)
        }
    }
}

private tailrec fun Throwable.unwrapCompletionException(): Throwable =
    when (this) {
        is CompletionException,
        is ExecutionException -> cause?.unwrapCompletionException() ?: this
        else -> this
    }
