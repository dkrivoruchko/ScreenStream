package info.dvkr.screenstream.model.appevent

import info.dvkr.screenstream.model.GlobalStatus

class GlobalStatusImpl(
        override @Volatile var isStreamRunning: Boolean = false,
        override @Volatile var error: Throwable? = null
) : GlobalStatus