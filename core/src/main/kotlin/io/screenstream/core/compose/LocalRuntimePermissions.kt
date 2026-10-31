package io.screenstream.core.compose

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import io.screenstream.core.permission.RuntimePermissions

/**
 * Provided by the app's Activity root; module UI receives the same [RuntimePermissions] capability.
 * Reading requires a provider. Service and process work use Context-based permission checks; the
 * Activity owns this request capability.
 */
public val LocalRuntimePermissions: ProvidableCompositionLocal<RuntimePermissions> = staticCompositionLocalOf {
    error("RuntimePermissions is not provided")
}
