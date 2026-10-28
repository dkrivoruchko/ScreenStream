package io.screenstream.streaming.compose

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import io.screenstream.streaming.permission.RuntimePermissions

/** Provided by the app's Activity root; modules receive the same permission host capability. */
public val LocalRuntimePermissions: ProvidableCompositionLocal<RuntimePermissions> = staticCompositionLocalOf {
    error("RuntimePermissions is not provided")
}
