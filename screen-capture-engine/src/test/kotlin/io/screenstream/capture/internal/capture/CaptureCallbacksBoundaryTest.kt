package io.screenstream.capture.internal.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

internal class CaptureCallbacksBoundaryTest {
    // Verification: CAP-06
    @Test
    fun ordinaryExceptionIsDeliveredExactlyOnceWithItsIdentityAndCause() {
        val identity = targetIdentity()
        val failure = IllegalStateException("callback failure")
        var callCount = 0
        var deliveredIdentity: CaptureCallbackIdentity? = null
        var deliveredFailure: Exception? = null

        runCaptureCallback(
            boundary = object : CaptureCallbackBoundary {
                override fun onCallbackException(identity: CaptureCallbackIdentity, failure: Exception) {
                    callCount += 1
                    deliveredIdentity = identity
                    deliveredFailure = failure
                }
            },
            identity = identity,
        ) {
            throw failure
        }

        assertEquals(1, callCount)
        assertSame(identity, deliveredIdentity)
        assertSame(failure, deliveredFailure)
    }

    // Verification: CAP-06
    @Test
    fun ordinaryExceptionFromBoundaryIsLocallyContained() {
        val expectedIdentity = targetIdentity()
        val callbackFailure = IllegalStateException("callback failure")
        val boundaryFailure = IllegalArgumentException("boundary failure")
        var callCount = 0

        runCaptureCallback(
            boundary = object : CaptureCallbackBoundary {
                override fun onCallbackException(identity: CaptureCallbackIdentity, failure: Exception) {
                    callCount += 1
                    assertSame(expectedIdentity, identity)
                    assertSame(callbackFailure, failure)
                    throw boundaryFailure
                }
            },
            identity = expectedIdentity,
        ) {
            throw callbackFailure
        }

        assertEquals(1, callCount)
    }

    // Verification: CAP-06
    @Test
    fun nonExceptionThrowablesEscapeUnchangedWithoutInvokingBoundary() {
        listOf(
            AssertionError("error"),
            DirectThrowable(),
        ).forEach { expected ->
            val identity = targetIdentity()
            var boundaryCallCount = 0

            val actual = try {
                runCaptureCallback(
                    boundary = object : CaptureCallbackBoundary {
                        override fun onCallbackException(identity: CaptureCallbackIdentity, failure: Exception) {
                            boundaryCallCount += 1
                        }
                    },
                    identity = identity,
                ) {
                    throw expected
                }
                null
            } catch (failure: Throwable) {
                failure
            }

            assertSame(expected, actual)
            assertEquals(0, boundaryCallCount)
        }
    }

    private fun targetIdentity(): CaptureCallbackIdentity =
        CaptureCallbackIdentity.Target(SourceCandidate().token)

    private class DirectThrowable : Throwable("direct non-Exception Throwable")
}
