package info.dvkr.screenstream.rtsp.ui

import androidx.compose.ui.Modifier

internal inline fun Modifier.conditional(condition: Boolean, modifier: Modifier.() -> Modifier): Modifier =
    if (condition) then(modifier(Modifier)) else this
