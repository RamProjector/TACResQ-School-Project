package com.tacresq.app.ui.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryCallTest {
    @Test
    fun unexpectedExceptionBecomesFailureResult() = runBlocking {
        val result = repositoryCallOrFailure<Int> {
            throw IllegalStateException("temporary repository failure")
        }

        assertTrue(result.isFailure)
        assertEquals("temporary repository failure", result.exceptionOrNull()?.message)
    }

    @Test
    fun cancellationIsRethrownInsteadOfConvertedToFailure() = runBlocking {
        val cancellation = CancellationException("screen scope ended")

        try {
            repositoryCallOrFailure<Int> { throw cancellation }
            throw AssertionError("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertTrue(actual === cancellation)
        }
    }

    @Test
    fun existingFailureResultIsPreserved() = runBlocking {
        val failure = IllegalArgumentException("validation failed")
        val result = repositoryCallOrFailure<Int> { Result.failure(failure) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() === failure)
    }
}
