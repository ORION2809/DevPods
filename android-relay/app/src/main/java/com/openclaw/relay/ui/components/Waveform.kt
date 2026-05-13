package com.openclaw.relay.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.theme.DevPodsColor

private val barHeights = listOf(16, 32, 52, 70, 46, 28, 58, 38, 22)

@Composable
fun Waveform(
    modifier: Modifier = Modifier,
    isAnimating: Boolean = true,
) {
    Row(
        modifier = modifier.height(76.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        barHeights.forEachIndexed { index, heightDp ->
            val infiniteTransition = rememberInfiniteTransition(label = "wave$index")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 400 + (index * 80),
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "waveScale$index",
            )

            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(heightDp.dp)
                    .background(
                        color = DevPodsColor.Mint.copy(
                            alpha = if (isAnimating) (0.6f + 0.4f * scale) else 0.78f,
                        ),
                        shape = RoundedCornerShape(percent = 50),
                    ),
            )
        }
    }
}
