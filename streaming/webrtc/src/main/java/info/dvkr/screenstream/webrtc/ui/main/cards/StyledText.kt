package info.dvkr.screenstream.webrtc.ui.main.cards

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

internal fun String.stylePlaceholder(placeholder: String, style: SpanStyle): AnnotatedString {
    val start = indexOf(placeholder)
    if (start < 0) return AnnotatedString(this)
    val spanStyles = listOf(AnnotatedString.Range(item = style, start = start, end = start + placeholder.length))
    return AnnotatedString(this, spanStyles = spanStyles)
}
