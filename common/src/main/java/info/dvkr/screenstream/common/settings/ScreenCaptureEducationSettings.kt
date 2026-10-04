package info.dvkr.screenstream.common.settings

/** The screen-capture education preference shared by streaming modes. */
public interface ScreenCaptureEducationSettings {
    /** Latest preference after the owning settings store has been initialized. */
    public val screenCaptureEducationCompleted: Boolean

    /** Saves education completion without changing the other streaming settings. */
    public suspend fun markEducationCompleted()
}
