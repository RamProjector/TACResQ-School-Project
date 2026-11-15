package com.tacresq.app.ui.common

import kotlinx.coroutines.CancellationException

/**
 * Converts unexpected repository exceptions into the same failure channel as Result-returning
 * repository methods, while preserving coroutine cancellation as control flow.
 */
internal suspend fun <T> repositoryCallOrFailure(
    call: suspend () -> Result<T>,
): Result<T> = try {
    call()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
