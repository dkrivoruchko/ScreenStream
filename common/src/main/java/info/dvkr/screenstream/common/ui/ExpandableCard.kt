package info.dvkr.screenstream.common.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
public fun ExpandableCard(
    headerContent: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    expandable: Boolean = true,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(modifier = modifier) {
        val expanded = remember(expandable) { mutableStateOf(false) }
        val iconRotation = remember { Animatable(0F) }
        val animatedAlpha = animateFloatAsState(
            targetValue = if (expanded.value) 1F else 0F,
            animationSpec = tween(easing = EaseInOutCubic),
            label = "ExpandableCardAlpha"
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .conditional(expandable) { clickable(role = Role.Button) { expanded.value = expanded.value.not() } }
                    .defaultMinSize(minHeight = 48.dp)
                    .fillMaxWidth()
            ) {
                headerContent.invoke(this)

                HorizontalDivider(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = animatedAlpha.value }
                        .fillMaxWidth()
                )

                if (expandable) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .rotate(iconRotation.value)
                            .align(Alignment.CenterStart)
                    )
                    LaunchedEffect(expanded.value) { iconRotation.animateTo(if (expanded.value) 90F else 0F) }
                    LaunchedEffect(Unit) { expanded.value = initiallyExpanded }
                }
            }
        }
        AnimatedVisibility(visible = expanded.value) {
            Column(modifier = contentModifier) {
                content.invoke(this)
            }
        }
    }
}