package info.dvkr.screenstream.common.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import info.dvkr.screenstream.common.R

public inline fun Modifier.conditional(condition: Boolean, modifier: Modifier.() -> Modifier): Modifier =
    if (condition) then(modifier(Modifier)) else this

public fun String.stylePlaceholder(placeholder: String, style: SpanStyle): AnnotatedString {
    val start = indexOf(placeholder)
    if (start < 0) return AnnotatedString(this)
    val spanStyles = listOf(AnnotatedString.Range(item = style, start = start, end = start + placeholder.length))
    return AnnotatedString(this, spanStyles = spanStyles)
}

public val RobotoMonoBold: FontFamily = FontFamily(Font(R.font.roboto_mono_bold, FontWeight.Bold))