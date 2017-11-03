package info.dvkr.screenstream.domain.globalstatus

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


interface GlobalStatus {

    val isStreamRunning: AtomicBoolean

    val error: AtomicReference<Throwable?>
}