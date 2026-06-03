package com.openclaw.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.DevPodsShapes
import com.openclaw.relay.ui.theme.DevPodsSpacing

enum class CardTone {
    Normal,
    Danger,
    Modal,
    DarkHero,
}

@Composable
fun DevPodsCard(
    modifier: Modifier = Modifier,
    accentColor: Color = DevPodsColor.Teal,
    tone: CardTone = CardTone.Normal,
    content: @Composable () -> Unit,
) {
    val surfaceTint = when (tone) {
        CardTone.Danger -> DevPodsColor.RedSoft.copy(alpha = 0.55f)
        CardTone.Modal -> DevPodsColor.Surface.copy(alpha = 0.90f)
        CardTone.DarkHero -> DevPodsColor.Ink.copy(alpha = 0.92f)
        CardTone.Normal -> accentColor.copy(alpha = 0.10f)
    }
    val borderColor = when (tone) {
        CardTone.Danger -> DevPodsColor.DangerBorder.copy(alpha = 0.50f)
        CardTone.DarkHero -> DevPodsColor.White.copy(alpha = 0.14f)
        else -> DevPodsColor.GlassBorder
    }
    val shadowColor = when (tone) {
        CardTone.DarkHero -> DevPodsColor.Ink.copy(alpha = 0.18f)
        else -> DevPodsColor.Ink.copy(alpha = 0.12f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .shadow(
                elevation = 18.dp,
                shape = DevPodsShapes.large,
                ambientColor = shadowColor,
                spotColor = shadowColor.copy(alpha = 0.10f),
            )
            .clip(DevPodsShapes.large)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        DevPodsColor.GlassStrong,
                        DevPodsColor.Surface.copy(alpha = 0.34f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = borderColor,
                shape = DevPodsShapes.large,
            ),
    ) {
        // Accent-tinted secondary background layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(surfaceTint),
        )

        // Glass shine overlay — fixed matchParentSize
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            DevPodsColor.White.copy(alpha = 0.50f),
                            Color.Transparent,
                        ),
                        center = androidx.compose.ui.geometry.Offset(180f, 80f),
                        radius = 400f,
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
                    .padding(horizontal = DevPodsSpacing.cardX, vertical = DevPodsSpacing.cardY),
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
