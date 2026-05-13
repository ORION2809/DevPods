package com.openclaw.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.DevPodsShapes

@Composable
fun DevPodsCard(
    modifier: Modifier = Modifier,
    accentColor: Color = DevPodsColor.Teal,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 18.dp,
                shape = DevPodsShapes.large,
                ambientColor = DevPodsColor.Ink.copy(alpha = 0.12f),
                spotColor = DevPodsColor.Ink.copy(alpha = 0.10f),
            )
            .clip(DevPodsShapes.large)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        DevPodsColor.White.copy(alpha = 0.74f),
                        DevPodsColor.Surface.copy(alpha = 0.34f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = DevPodsColor.White.copy(alpha = 0.72f),
                shape = DevPodsShapes.large,
            ),
    ) {
        // Glass shine overlay (approximates CSS ::after pseudo-element)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            DevPodsColor.White.copy(alpha = 0.50f),
                            Color.Transparent,
                        ),
                        center = androidx.compose.ui.geometry.Offset(0.18f, 0.08f),
                        radius = 0.6f,
                    ),
                ),
        )

        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Accent bar with glow
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.92f),
                                accentColor.copy(alpha = 0.42f),
                            ),
                        ),
                    )
                    .shadow(
                        elevation = 12.dp,
                        shape = DevPodsShapes.small,
                        ambientColor = accentColor.copy(alpha = 0.35f),
                        spotColor = accentColor.copy(alpha = 0.25f),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
fun DevPodsHeroCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 18.dp,
                shape = DevPodsShapes.extraLarge,
                ambientColor = DevPodsColor.Ink.copy(alpha = 0.18f),
                spotColor = DevPodsColor.Ink.copy(alpha = 0.18f),
            )
            .clip(DevPodsShapes.extraLarge)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        DevPodsColor.Ink.copy(alpha = 0.96f),
                        DevPodsColor.Ink2.copy(alpha = 0.88f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = DevPodsColor.White.copy(alpha = 0.14f),
                shape = DevPodsShapes.extraLarge,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            content()
        }
    }
}
