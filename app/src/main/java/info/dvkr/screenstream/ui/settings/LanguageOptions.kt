package info.dvkr.screenstream.ui.settings

import android.annotation.SuppressLint
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
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
import info.dvkr.screenstream.R
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

@Composable
internal fun LanguageOptions(onLanguageSelect: (Locale?) -> Unit) {
    val packageName = LocalContext.current.packageName
    val resources = LocalResources.current
    val appLocale = AppCompatDelegate.getApplicationLocales().get(0)
    val languageOptions = remember(resources, packageName) { loadLanguageOptions(resources, packageName) }

    LanguageRow(
        displayLanguage = stringResource(R.string.app_pref_language_system_default),
        selected = appLocale == null,
        onClick = { onLanguageSelect(null) },
    )

    languageOptions.forEach { (locale, displayLanguage) ->
        LanguageRow(
            displayLanguage = displayLanguage,
            selected = appLocale == locale,
            onClick = { onLanguageSelect(locale) },
        )
    }
}

@SuppressLint("DiscouragedApi")
private fun loadLanguageOptions(resources: Resources, packageName: String): List<Pair<Locale, String>> {
    val localeConfigId = resources.getIdentifier("_generated_res_locale_config", "xml", packageName)
    return resources.getXml(localeConfigId).use { parser ->
        val locales = mutableListOf<Locale>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                val tag = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                if (tag != null) locales.add(Locale.forLanguageTag(tag))
            }
            parser.next()
        }
        locales
    }.map { locale ->
        val displayLanguage = locale.getDisplayLanguage(locale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        val countrySuffix = locale.getDisplayCountry(locale).let { if (it.isNotBlank()) " ($it)" else "" }
        locale to "$displayLanguage$countrySuffix"
    }.sortedBy { it.second }
}

@Composable
private fun LanguageRow(
    displayLanguage: String,
    selected: Boolean,
    onClick: () -> Unit,
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
                .padding(start = 8.dp, end = if (selected) 0.dp else 8.dp)
                .weight(1F)
        )
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.app_icon_check_small),
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
