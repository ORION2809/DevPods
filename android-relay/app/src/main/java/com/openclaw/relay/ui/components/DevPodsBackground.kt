package com.openclaw.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
        // Anchored mint blob — top-left, reference position
        Box(
            modifier = Modifier
                .size(260.dp)
                .offset(x = (-92).dp, y = (-88).dp)
                .clip(CircleShape)
                .background(DevPodsColor.GlowMint.copy(alpha = 0.14f)),
        )

        // Anchored amber blob — top-right, reference position
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(220.dp)
                .offset(x = 76.dp, y = 38.dp)
                .clip(CircleShape)
                .background(DevPodsColor.GlowAmber.copy(alpha = 0.13f)),
        )

        content()
    }
}
