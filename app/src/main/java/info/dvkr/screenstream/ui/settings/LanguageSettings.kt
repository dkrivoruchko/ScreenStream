package info.dvkr.screenstream.ui.settings

import android.annotation.SuppressLint
import android.content.res.Resources
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import info.dvkr.screenstream.R
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

@Composable
internal fun LanguageSettings(
    onBack: () -> Unit,
    @MainThread onLanguageSelect: (String?) -> Unit = { languageTag ->
        if (languageTag != null) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        else AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val appLanguageTag = remember { AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() }

    val languageOptions = remember(resources, context.packageName) {
        loadLanguageOptions(resources, context.packageName)
    }

    SettingsDetailLayout(
        title = stringResource(R.string.app_pref_locale),
        onBack = onBack,
        modifier = Modifier.fillMaxWidth(),
    ) {

        Column(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .selectableGroup()
                .verticalScroll(rememberScrollState())
        ) {
            LanguageRow(
                displayLanguage = stringResource(id = R.string.app_pref_locale_default),
                selected = appLanguageTag == null,
                onClick = { onLanguageSelect(null) }
            )

            languageOptions.forEach { (tag, displayLanguage) ->
                LanguageRow(
                    displayLanguage = displayLanguage,
                    selected = appLanguageTag == tag,
                    onClick = { onLanguageSelect(tag) }
                )
            }
        }
    }
}

@SuppressLint("DiscouragedApi")
private fun loadLanguageOptions(resources: Resources, packageName: String): List<Pair<String, String>> {
    val localeConfigId = resources.getIdentifier("_generated_res_locale_config", "xml", packageName)
    return resources.getXml(localeConfigId).use { parser ->
        val tags = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                tags.add(parser.getAttributeValue(0))
            }
            parser.next()
        }
        tags.toList()
    }.map { tag ->
        val locale = Locale.forLanguageTag(tag)
        val displayLanguage = locale.getDisplayLanguage(locale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        val countrySuffix = locale.getDisplayCountry(locale).let { if (it.isNotBlank()) " ($it)" else "" }
        tag to "$displayLanguage$countrySuffix"
    }.sortedBy { it.second }
}

@Composable
private fun LanguageRow(
    displayLanguage: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayLanguage,
            modifier = Modifier
                .padding(start = 24.dp, end = if (selected) 0.dp else 24.dp)
                .weight(1F)
        )
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.check_small_24px),
                contentDescription = null,
                modifier = Modifier.padding(end = 24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
