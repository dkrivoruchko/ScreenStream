package info.dvkr.screenstream.model.appevent

import info.dvkr.screenstream.model.GlobalStatus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GlobalStatusImpl(
        override var isStreamRunning: AtomicBoolean = AtomicBoolean(false),
        override var error: AtomicReference<Throwable?> = AtomicReference(null)
) : GlobalStatus