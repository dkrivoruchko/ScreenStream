package info.dvkr.screenstream.webrtc.ui

import androidx.compose.ui.Modifier

internal inline fun Modifier.conditional(condition: Boolean, modifier: Modifier.() -> Modifier): Modifier =
    if (condition) then(modifier(Modifier)) else this
