package info.dvkr.screenstream.ui.tabs.settings.app.settings.general

import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.common.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object DynamicTheme : ModuleSettings.Item {
    override val id: String = AppSettings.Key.DYNAMIC_THEME.name
    override val position: Int = 2
    override val available: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.app_pref_dynamic_theme).contains(text, ignoreCase = true) ||
                getString(R.string.app_pref_dynamic_theme_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val appSettings = koinInject<AppSettings>()
        val appSettingsState = appSettings.data.collectAsStateWithLifecycle()
        val dynamicTheme = appSettingsState.value.dynamicTheme

        DynamicThemeUI(horizontalPadding, dynamicTheme) {
            coroutineScope.launch { appSettings.updateData { copy(dynamicTheme = it) } }
        }
    }
}

@Composable
private fun DynamicThemeUI(
    horizontalPadding: Dp,
    dynamicTheme: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = dynamicTheme, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_SymbolColor, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.app_pref_dynamic_theme),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.app_pref_dynamic_theme_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = dynamicTheme, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private var Icon_SymbolColor: ImageVector = materialIcon(name = "SymbolColor") {
    materialPath {
        moveTo(12.024f, 1.016f)
        curveTo(5.955f, 1.016f, 0.98f, 5.978f, 0.98f, 12.047f)
        verticalLineToRelative(0.675f)
        curveToRelative(0.141f, 2.37f, 2.998f, 2.81f, 4.71f, 1.099f)
        curveToRelative(2.758f, -2.451f, 6.595f, 1.386f, 4.144f, 4.144f)
        curveToRelative(-1.727f, 1.821f, -1.24f, 4.82f, 1.256f, 5.024f)
        horizontalLineToRelative(0.941f)
        curveToRelative(6.07f, 0f, 10.989f, -4.92f, 10.989f, -10.989f)
        curveToRelative(0f, -6.069f, -4.92f, -10.989f, -10.989f, -10.989f)
        close()
        moveToRelative(0f, 20.523f)
        horizontalLineToRelative(-0.7f)
        curveToRelative(-0.226f, 0.025f, -0.58f, -0.157f, -0.737f, -0.293f)
        arcToRelative(0.88f, 0.88f, 0f, isMoreThanHalf = false, isPositiveArc = true, -0.235f, -0.47f)
        arcToRelative(1.947f, 1.947f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0.55f, -1.696f)
        arcToRelative(4.505f, 4.505f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, -6.28f)
        arcToRelative(4.505f, 4.505f, 0f, isMoreThanHalf = false, isPositiveArc = false, -6.374f, 0f)
        arcToRelative(1.57f, 1.57f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1.413f, 0.534f)
        arcToRelative(0.644f, 0.644f, 0f, isMoreThanHalf = false, isPositiveArc = true, -0.345f, -0.188f)
        arcToRelative(0.66f, 0.66f, 0f, isMoreThanHalf = false, isPositiveArc = true, -0.157f, -0.455f)
        verticalLineToRelative(-0.581f)
        curveToRelative(0f, -8.392f, 10.145f, -12.593f, 16.078f, -6.66f)
        reflectiveCurveToRelative(1.732f, 16.079f, -6.66f, 16.079f)
        close()
        moveToRelative(1.514f, -15.78f)
        curveToRelative(0f, 2.093f, -3.14f, 2.093f, -3.14f, 0f)
        curveToRelative(0f, -2.094f, 3.14f, -2.094f, 3.14f, 0f)
        close()
        moveToRelative(4.71f, 11f)
        curveToRelative(0f, 2.092f, -3.14f, 2.092f, -3.14f, 0f)
        curveToRelative(0f, -2.094f, 3.14f, -2.094f, 3.14f, 0f)
        close()
        moveTo(7.259f, 8.908f)
        curveToRelative(2.093f, 0f, 2.093f, -3.14f, 0f, -3.14f)
        reflectiveCurveToRelative(-2.093f, 3.14f, 0f, 3.14f)
        close()
        moveToRelative(10.989f, -1.57f)
        curveToRelative(0f, 2.093f, -3.14f, 2.093f, -3.14f, 0f)
        reflectiveCurveToRelative(3.14f, -2.093f, 3.14f, 0f)
        close()
        moveToRelative(1.57f, 4.703f)
        curveToRelative(0f, 2.093f, -3.14f, 2.093f, -3.14f, 0f)
        reflectiveCurveToRelative(3.14f, -2.093f, 3.14f, 0f)
        close()
    }
}