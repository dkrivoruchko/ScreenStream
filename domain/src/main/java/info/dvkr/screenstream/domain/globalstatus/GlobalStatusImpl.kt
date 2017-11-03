package info.dvkr.screenstream.domain.globalstatus

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GlobalStatusImpl(
        override val isStreamRunning: AtomicBoolean = AtomicBoolean(false),
        override val error: AtomicReference<Throwable?> = AtomicReference(null)
) : GlobalStatus