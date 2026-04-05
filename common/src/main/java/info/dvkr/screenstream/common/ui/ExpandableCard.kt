package info.dvkr.screenstream.common.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.R

@Composable
public fun ExpandableCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    headerContent: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    expandable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(modifier = modifier) {
        val contentVisible = expandable && expanded
        val animatedAlpha = animateFloatAsState(
            targetValue = if (contentVisible) 1F else 0F,
            animationSpec = tween(easing = EaseInOutCubic),
            label = "ExpandableCardAlpha"
        )
        val animatedRotation = animateFloatAsState(
            targetValue = if (contentVisible) 90F else 0F,
            animationSpec = tween(easing = EaseInOutCubic),
            label = "ExpandableCardRotation"
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .conditional(expandable) { clickable(role = Role.Button) { onExpandedChange(expanded.not()) } }
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
                        painter = painterResource(R.drawable.chevron_right_24px),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .graphicsLayer { rotationZ = animatedRotation.value }
                            .align(Alignment.CenterStart)
                    )
                }
            }
        }
        AnimatedVisibility(visible = contentVisible) {
            Column(modifier = contentModifier) {
                content.invoke(this)
            }
        }
    }
}
