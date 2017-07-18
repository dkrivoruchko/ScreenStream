package info.dvkr.screenstream.model

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


interface GlobalStatus {

    var isStreamRunning: Boolean

    var error: Throwable?
}