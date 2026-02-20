package info.dvkr.screenstream.common.analytics

import java.util.Locale

public object StreamingAnalyticsMappers {

    public fun mapEntryPoint(rawSource: String?): EntryPoint {
        val normalized: String = rawSource.orEmpty().trim().lowercase(Locale.US)
        return when (normalized) {
            "button" -> EntryPoint.BUTTON
            "web" -> EntryPoint.WEB
            else -> EntryPoint.UNKNOWN
        }
    }

    public fun mapStartFailGroup(rawClassification: String?): StartFailGroup {
        val normalized: String = rawClassification.orEmpty().trim().lowercase(Locale.US)
        return when (normalized) {
            "permission_denied", "castpermissionsdenied" -> StartFailGroup.PERMISSION_DENIED
            "busy" -> StartFailGroup.BUSY
            "blocked" -> StartFailGroup.BLOCKED
            "fatal" -> StartFailGroup.FATAL
            else -> StartFailGroup.UNKNOWN
        }
    }

    public fun mapStopReasonGroup(rawReason: String?): StopReasonGroup {
        val normalized: String = rawReason.orEmpty().trim().lowercase(Locale.US)
        return when {
            normalized.isBlank() -> StopReasonGroup.UNKNOWN
            normalized.contains("user action") -> StopReasonGroup.USER
            normalized.contains("tileactionservice.onclick") -> StopReasonGroup.USER
            normalized.contains("startstopfromwebpage") -> StopReasonGroup.USER
            normalized.contains("screenoff") -> StopReasonGroup.SYSTEM
            normalized.contains("projectioncoordinator.onstop") -> StopReasonGroup.SYSTEM
            normalized.contains("configurationchange") -> StopReasonGroup.SYSTEM
            normalized.contains("modechanged") -> StopReasonGroup.SYSTEM
            normalized.contains("rtspclientdisconnect") -> StopReasonGroup.SYSTEM
            normalized.contains("destroy") -> StopReasonGroup.SYSTEM
            normalized.contains("capturefatal") -> StopReasonGroup.ERROR
            normalized.contains("socketsignalingerror") -> StopReasonGroup.ERROR
            normalized.contains("error") -> StopReasonGroup.ERROR
            normalized.contains("exception") -> StopReasonGroup.ERROR
            normalized.contains("fatal") -> StopReasonGroup.ERROR
            normalized.contains("failed") -> StopReasonGroup.ERROR
            else -> StopReasonGroup.UNKNOWN
        }
    }

    public fun mapDurationBucket(durationMs: Long): DurationBucket {
        val normalizedDurationMs: Long = durationMs.coerceAtLeast(0L)
        return when {
            normalizedDurationMs < 60_000L -> DurationBucket.LT_1M
            normalizedDurationMs < 5 * 60_000L -> DurationBucket.FROM_1_TO_5M
            normalizedDurationMs < 15 * 60_000L -> DurationBucket.FROM_5_TO_15M
            normalizedDurationMs < 60 * 60_000L -> DurationBucket.FROM_15_TO_60M
            else -> DurationBucket.GT_60M
        }
    }

    public fun mapMaxActiveConsumersBucket(maxActiveConsumers: Int): MaxActiveConsumersBucket {
        val normalizedMaxActiveConsumers: Int = maxActiveConsumers.coerceAtLeast(0)
        return when {
            normalizedMaxActiveConsumers == 0 -> MaxActiveConsumersBucket.ZERO
            normalizedMaxActiveConsumers == 1 -> MaxActiveConsumersBucket.ONE
            normalizedMaxActiveConsumers <= 3 -> MaxActiveConsumersBucket.FROM_2_TO_3
            else -> MaxActiveConsumersBucket.FOUR_PLUS
        }
    }

    public fun toFlag(value: Boolean): Int = if (value) 1 else 0
}
