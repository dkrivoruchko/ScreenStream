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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    override fun ListUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) =
        AppLocaleUI(horizontalPadding, onDetailShow)

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) =
        AppLocaleDetailsUI(headerContent)
}

@Composable
private fun AppLocaleUI(
    horizontalPadding: Dp,
    onDetailShow: () -> Unit
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .clickable(role = Role.Button) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    onDetailShow.invoke()
                } else {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_LOCALE_SETTINGS).setData(Uri.fromParts("package", context.packageName, null))
                    )
                }
            }
            .padding(horizontal = horizontalPadding + 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Translate,
            contentDescription = stringResource(id = R.string.app_pref_locale),
            modifier = Modifier.padding(end = 16.dp)
        )
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
    scope: CoroutineScope = rememberCoroutineScope(),
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
                selected = appLanguageTag == null
            ) {
                scope.launch {
                    withContext(NonCancellable) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
                }
            }

            languageTags.forEach { (tag, displayLanguage) ->
                LanguageRow(
                    displayLanguage = displayLanguage,
                    selected = appLanguageTag == tag
                ) {
                    scope.launch {
                        withContext(NonCancellable) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
                    }
                }
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
            .selectable(
                selected = selected,
                onClick = { onClick.invoke() },
                role = Role.RadioButton
            )
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Text(
                text = displayLanguage,
                modifier = Modifier
                    .padding(start = 24.dp)
                    .weight(1F)
            )
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.padding(end = 24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(text = displayLanguage, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}