package io.screenstream.core.compose

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import io.screenstream.core.permission.RuntimePermissions

/**
 * Activity-root [RuntimePermissions] from [RuntimePermissionHost]; reading without a provider throws.
 * Services and process work use the Context-based `RuntimePermission.isGranted` instead.
 */
public val LocalRuntimePermissions: ProvidableCompositionLocal<RuntimePermissions> = staticCompositionLocalOf {
    error("RuntimePermissions is not provided")
}
