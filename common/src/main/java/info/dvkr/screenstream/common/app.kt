package info.dvkr.screenstream.common

public data class AppState(public val isBusy: Boolean = true, public val isStreaming: Boolean = false)

public sealed class AppEvent {
    public data object StartStream : AppEvent()
    public data object StopStream : AppEvent()
}