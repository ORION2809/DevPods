package com.openclaw.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.openclaw.relay.ui.theme.DevPodsColor

@Composable
fun DevPodsBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DevPodsColor.Background),
    ) {
        // Green glow top-left
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            DevPodsColor.GlowGreen.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                        radius = 900f,
                    ),
                ),
        )
        // Amber glow top-right
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            DevPodsColor.GlowAmber.copy(alpha = 0.14f),
                            Color.Transparent,
                        ),
                        radius = 800f,
                    ),
                ),
        )
        content()
    }
}
