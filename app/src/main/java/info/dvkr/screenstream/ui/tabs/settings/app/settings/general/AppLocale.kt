package info.dvkr.screenstream.ui.tabs.settings.app.settings.general

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.ModuleSettings
import kotlinx.coroutines.CoroutineScope
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

internal object AppLocale : ModuleSettings.Item {
    override val id: String = "APP_LOCALE"
    override val position: Int = 0
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.app_pref_locale).contains(text, ignoreCase = true) ||
                getString(R.string.app_pref_locale_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val context = LocalContext.current

        AppLocaleUI(horizontalPadding) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onDetailShow.invoke()
            } else {
                context.startActivity(
                    Intent(Settings.ACTION_APP_LOCALE_SETTINGS).setData(Uri.fromParts("package", context.packageName, null))
                )
            }
        }
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        AppLocaleDetailsUI(headerContent) {
            if (it != null) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it))
            else AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }
}

@Composable
private fun AppLocaleUI(
    horizontalPadding: Dp,
    onDetailShow: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onDetailShow)
            .padding(horizontal = horizontalPadding + 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Translate, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1.0F)) {
            Text(
                text = stringResource(id = R.string.app_pref_locale),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.app_pref_locale_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@SuppressLint("DiscouragedApi")
@Composable
private fun AppLocaleDetailsUI(
    headerContent: @Composable (String) -> Unit,
    onLanguageSelected: (String?) -> Unit
) {
    val context = LocalContext.current
    val appLanguageTag = remember { AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() }

    val languageTags = remember {
        val localeConfigId = context.resources.getIdentifier("_generated_res_locale_config", "xml", context.packageName)
        context.resources.getXml(localeConfigId).run {
            val tags = mutableListOf<String>()
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && name == "locale") {
                    tags.add(getAttributeValue(0))
                }
                next()
            }
            tags.toList()
        }.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            val displayLanguage = locale.getDisplayLanguage(locale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            val displayCountry = locale.getDisplayCountry(locale).let { if (it.isNotBlank()) " ($it)" else "" }
            tag to "$displayLanguage$displayCountry"
        }.sortedBy { it.second }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        headerContent.invoke(stringResource(id = R.string.app_pref_locale))

        Column(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .selectableGroup()
                .verticalScroll(rememberScrollState())
        ) {
            LanguageRow(
                displayLanguage = stringResource(id = R.string.app_pref_locale_default),
                selected = appLanguageTag == null,
                onClick = { onLanguageSelected.invoke(null) }
            )

            languageTags.forEach { (tag, displayLanguage) ->
                LanguageRow(
                    displayLanguage = displayLanguage,
                    selected = appLanguageTag == tag,
                    onClick = { onLanguageSelected.invoke(tag) }
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(
    displayLanguage: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Text(text = displayLanguage, modifier = Modifier.padding(start = 24.dp).weight(1F))
            Icon(
                imageVector = Icon_Check,
                contentDescription = null,
                modifier = Modifier.padding(end = 24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(text = displayLanguage, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}

private val Icon_Translate: ImageVector = materialIcon(name = "Filled.Translate") {
    materialPath {
        moveTo(12.87f, 15.07f)
        lineToRelative(-2.54f, -2.51f)
        lineToRelative(0.03f, -0.03f)
        curveToRelative(1.74f, -1.94f, 2.98f, -4.17f, 3.71f, -6.53f)
        lineTo(17.0f, 6.0f)
        lineTo(17.0f, 4.0f)
        horizontalLineToRelative(-7.0f)
        lineTo(10.0f, 2.0f)
        lineTo(8.0f, 2.0f)
        verticalLineToRelative(2.0f)
        lineTo(1.0f, 4.0f)
        verticalLineToRelative(1.99f)
        horizontalLineToRelative(11.17f)
        curveTo(11.5f, 7.92f, 10.44f, 9.75f, 9.0f, 11.35f)
        curveTo(8.07f, 10.32f, 7.3f, 9.19f, 6.69f, 8.0f)
        horizontalLineToRelative(-2.0f)
        curveToRelative(0.73f, 1.63f, 1.73f, 3.17f, 2.98f, 4.56f)
        lineToRelative(-5.09f, 5.02f)
        lineTo(4.0f, 19.0f)
        lineToRelative(5.0f, -5.0f)
        lineToRelative(3.11f, 3.11f)
        lineToRelative(0.76f, -2.04f)
        close()
        moveTo(18.5f, 10.0f)
        horizontalLineToRelative(-2.0f)
        lineTo(12.0f, 22.0f)
        horizontalLineToRelative(2.0f)
        lineToRelative(1.12f, -3.0f)
        horizontalLineToRelative(4.75f)
        lineTo(21.0f, 22.0f)
        horizontalLineToRelative(2.0f)
        lineToRelative(-4.5f, -12.0f)
        close()
        moveTo(15.88f, 17.0f)
        lineToRelative(1.62f, -4.33f)
        lineTo(19.12f, 17.0f)
        horizontalLineToRelative(-3.24f)
        close()
    }
}

private val Icon_Check: ImageVector = materialIcon(name = "Filled.Check") {
    materialPath {
        moveTo(9.0f, 16.17f)
        lineTo(4.83f, 12.0f)
        lineToRelative(-1.42f, 1.41f)
        lineTo(9.0f, 19.0f)
        lineTo(21.0f, 7.0f)
        lineToRelative(-1.41f, -1.41f)
        close()
    }
}