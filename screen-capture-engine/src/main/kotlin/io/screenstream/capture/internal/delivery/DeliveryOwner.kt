package io.screenstream.capture.internal.delivery

import io.screenstream.capture.EncodedImageFrame
import io.screenstream.capture.ScreenCaptureEffectiveParameters
import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import io.screenstream.capture.internal.runtime.SerialTaskSlot
import io.screenstream.capture.internal.storage.PublishedFrame

internal interface DeliveryFactSink {
    fun offer(fact: DeliveryFact)
    fun stageClosed(fact: DeliveryFact.Closed): DeliveryClosedStage
}

internal fun interface DeliveryClosedStage {
    fun ready()
}

internal class DeliveryHandoffToken(internal val registrationId: Long) {
    init {
        require(registrationId > 0L)
    }
}

internal sealed interface DeliveryOffer {
    class Accepted(internal val handoff: DeliveryHandoffToken) : DeliveryOffer
    data object Occupied : DeliveryOffer
    data object Cutoff : DeliveryOffer
    class Rejected(internal val handoff: DeliveryHandoffToken, internal val cause: Exception?) : DeliveryOffer
}

internal sealed interface DeliveryCutoff {
    data object NoHandoff : DeliveryCutoff
    data object CutoffBeforeEntry : DeliveryCutoff
    data object Entered : DeliveryCutoff
}

internal sealed interface DeliveryFact {
    val handoff: DeliveryHandoffToken

    class CallbackFailure(override val handoff: DeliveryHandoffToken, internal val exception: Exception) : DeliveryFact
    class Closed(override val handoff: DeliveryHandoffToken, internal val outcome: Outcome) : DeliveryFact {
        internal sealed interface Outcome {
            data object CallbackReturned : Outcome
            data object CutoffBeforeEntry : Outcome
            class InternalFailure(internal val cause: Exception) : Outcome
        }
    }
}

/**
 * Sole physical owner of serial callback handoff and callback-scoped frame access.
 *
 * The owner has one queue-less handoff. Dispatch acceptance proves neither callback entry nor return. Before every
 * public frame access the borrow verifies both its open interval and exact callback thread, and the borrow is revoked
 * on every actual callback exit. Cutoff prevents a queued callback from entering but never interrupts an entered
 * callback or treats elapsed time, terminal state, or reference release as a return receipt.
 */
internal class DeliveryOwner(workerDispatcher: NonInlineDispatcher, private val factSink: DeliveryFactSink) {
    private enum class Entry { Queued, Entered, CutoffInert, Returned, }

    private class BorrowedFrame(frame: PublishedFrame) : EncodedImageFrame.Access {
        private val accessGate = Any()
        private var retainedFrame: PublishedFrame? = frame
        private var callbackThread: Thread? = null
        private var open = false

        val frame: EncodedImageFrame = EncodedImageFrame.create(this)

        fun openOn(thread: Thread) {
            synchronized(accessGate) {
                check(!open && (callbackThread == null) && (retainedFrame != null))
                callbackThread = thread
                open = true
            }
        }

        fun revoke() {
            synchronized(accessGate) {
                open = false
                callbackThread = null
                retainedFrame = null
            }
        }

        override fun byteCount(): Int = checkedFrame().payload.byteCount

        override fun effectiveParameters(): ScreenCaptureEffectiveParameters = checkedFrame().effectiveParameters

        override fun sequence(): Long = checkedFrame().sequence

        override fun timestampElapsedRealtimeNanos(): Long = checkedFrame().timestampElapsedRealtimeNanos

        override fun copyTo(destination: ByteArray, destinationOffset: Int): Int = checkedFrame().payload.copyTo(destination, destinationOffset)

        override fun toByteArray(): ByteArray = checkedFrame().payload.toByteArray()

        private fun checkedFrame(): PublishedFrame = synchronized(accessGate) {
            val frame = retainedFrame
            if (!open || (frame == null)) {
                throw IllegalStateException("EncodedImageFrame is valid only during its callback body")
            }
            if (Thread.currentThread() !== callbackThread) {
                throw IllegalStateException("EncodedImageFrame is valid only on its callback thread")
            }
            frame
        }
    }

    private class Handoff(val token: DeliveryHandoffToken, callback: (EncodedImageFrame) -> Unit, frame: PublishedFrame) {
        val borrow = BorrowedFrame(frame)
        var callback: ((EncodedImageFrame) -> Unit)? = callback
        var entry: Entry = Entry.Queued
        var callbackThread: Thread? = null
        var closedOutcome: DeliveryFact.Closed.Outcome? = null
    }

    private val ownerGate = Any()
    private val serialSlot = SerialTaskSlot(workerDispatcher)
    private var retired = false
    private var current: Handoff? = null

    internal fun offer(token: DeliveryHandoffToken, callback: (EncodedImageFrame) -> Unit, frame: PublishedFrame): DeliveryOffer {
        val handoff = Handoff(token = token, callback = callback, frame = frame)
        synchronized(ownerGate) {
            if (retired) return DeliveryOffer.Cutoff
            if (current != null) return DeliveryOffer.Occupied
            current = handoff
        }

        val submission = try {
            serialSlot.trySubmit(task = { execute(handoff) }) { finishReleased(handoff) }
        } catch (failure: Exception) {
            rejectBeforeEntry(handoff)
            return DeliveryOffer.Rejected(handoff.token, failure)
        }
        return when (submission) {
            SerialTaskSlot.Submission.Accepted -> DeliveryOffer.Accepted(handoff.token)
            SerialTaskSlot.Submission.Occupied -> {
                rejectBeforeEntry(handoff)
                DeliveryOffer.Rejected(handoff.token, cause = null)
            }

            is SerialTaskSlot.Submission.Rejected -> {
                rejectBeforeEntry(handoff)
                DeliveryOffer.Rejected(handoff.token, submission.cause)
            }
        }
    }

    internal fun cutoff(registrationId: Long): DeliveryCutoff {
        require(registrationId > 0L)
        return synchronized(ownerGate) {
            val handoff = current
            if ((handoff == null) || (handoff.token.registrationId != registrationId)) {
                DeliveryCutoff.NoHandoff
            } else {
                when (handoff.entry) {
                    Entry.Queued -> {
                        handoff.entry = Entry.CutoffInert
                        DeliveryCutoff.CutoffBeforeEntry
                    }

                    Entry.CutoffInert -> DeliveryCutoff.CutoffBeforeEntry
                    Entry.Entered -> DeliveryCutoff.Entered
                    Entry.Returned -> DeliveryCutoff.Entered
                }
            }
        }
    }

    internal fun isEnteredCallbackThread(registrationId: Long): Boolean {
        if (registrationId <= 0L) return false
        return synchronized(ownerGate) {
            val handoff = current
            (handoff?.token?.registrationId == registrationId) && (handoff.entry == Entry.Entered) && (handoff.callbackThread === Thread.currentThread())
        }
    }

    internal fun retire(): DeliveryCutoff = synchronized(ownerGate) {
        retired = true
        val handoff = current ?: return@synchronized DeliveryCutoff.NoHandoff
        when (handoff.entry) {
            Entry.Queued -> {
                handoff.entry = Entry.CutoffInert
                DeliveryCutoff.CutoffBeforeEntry
            }

            Entry.CutoffInert -> DeliveryCutoff.CutoffBeforeEntry
            Entry.Entered, Entry.Returned -> DeliveryCutoff.Entered
        }
    }

    private fun execute(handoff: Handoff) {
        val entered = synchronized(ownerGate) {
            if ((current !== handoff) || (handoff.entry == Entry.CutoffInert) || retired) {
                if ((current === handoff) && (handoff.entry == Entry.Queued)) {
                    handoff.entry = Entry.CutoffInert
                }
                false
            } else {
                check(handoff.entry == Entry.Queued)
                handoff.entry = Entry.Entered
                handoff.callbackThread = Thread.currentThread()
                true
            }
        }
        if (!entered) {
            handoff.borrow.revoke()
            handoff.closedOutcome = DeliveryFact.Closed.Outcome.CutoffBeforeEntry
            return
        }

        var borrowOpened = false
        val openFailure = try {
            handoff.borrow.openOn(Thread.currentThread())
            borrowOpened = true
            null
        } catch (failure: Exception) {
            failure
        } finally {
            if (!borrowOpened) handoff.borrow.revoke()
        }
        if (openFailure != null) {
            handoff.callback = null
            handoff.closedOutcome = DeliveryFact.Closed.Outcome.InternalFailure(openFailure)
            synchronized(ownerGate) {
                if ((current === handoff) && (handoff.entry == Entry.Entered)) {
                    handoff.callbackThread = null
                    handoff.entry = Entry.Returned
                }
            }
            return
        }

        val callbackFailure = try {
            val callback = checkNotNull(handoff.callback)
            callback(handoff.borrow.frame)
            null
        } catch (failure: Exception) {
            failure
        } finally {
            handoff.borrow.revoke()
        }
        handoff.callback = null
        handoff.closedOutcome = if (callbackFailure == null) {
            DeliveryFact.Closed.Outcome.CallbackReturned
        } else {
            val factFailure = try {
                factSink.offer(DeliveryFact.CallbackFailure(handoff.token, callbackFailure))
                null
            } catch (reportFailure: Exception) {
                reportFailure
            }
            if (factFailure == null) {
                DeliveryFact.Closed.Outcome.CallbackReturned
            } else {
                DeliveryFact.Closed.Outcome.InternalFailure(factFailure)
            }
        }
        synchronized(ownerGate) {
            if ((current === handoff) && (handoff.entry == Entry.Entered)) {
                handoff.callbackThread = null
                handoff.entry = Entry.Returned
            }
        }
    }

    private fun finishReleased(handoff: Handoff) {
        val outcome = synchronized(ownerGate) {
            if (current !== handoff) return
            check((handoff.entry == Entry.Returned) || (handoff.entry == Entry.CutoffInert))
            if (handoff.entry == Entry.CutoffInert) handoff.callback = null
            checkNotNull(handoff.closedOutcome)
        }
        val closedStage = try {
            factSink.stageClosed(DeliveryFact.Closed(handoff.token, outcome))
        } catch (_: Exception) {
            return
        }
        synchronized(ownerGate) {
            if (current !== handoff) return
            current = null
        }
        try {
            closedStage.ready()
        } catch (_: Exception) {
        }
    }

    private fun rejectBeforeEntry(handoff: Handoff) {
        handoff.borrow.revoke()
        synchronized(ownerGate) {
            if (current === handoff) current = null
        }
    }
}
