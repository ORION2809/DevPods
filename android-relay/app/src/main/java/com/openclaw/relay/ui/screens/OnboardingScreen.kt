package com.openclaw.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.components.ButtonStyle
import com.openclaw.relay.ui.components.DevPodsButton
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.DevPodsWordmarkLight
import com.openclaw.relay.ui.components.Waveform
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.DevPodsSpacing
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable
fun OnboardingScreen(
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DevPodsSpacing.screenX),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Hero visual — circular earbud composition
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Large circular backdrop
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .background(DevPodsColor.Surface2.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left earbud
                    Box(
                        modifier = Modifier
                            .size(width = 28.dp, height = 64.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(DevPodsColor.Ink),
                    )
                    // Waveform
                    Waveform(
                        modifier = Modifier.width(120.dp),
                        isAnimating = true,
                    )
                    // Right earbud
                    Box(
                        modifier = Modifier
                            .size(width = 28.dp, height = 64.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(DevPodsColor.Ink),
                    )
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            DevPodsWordmarkLight(
                modifier = Modifier.width(188.dp),
            )
        }

        // Hero headline
        Text(
            text = "Talk to your workspace through ordinary earbuds.",
            style = MaterialTheme.typography.headlineSmall,
            color = DevPodsColor.Ink,
            modifier = Modifier.semantics { heading() },
        )

        // Product promise body
        Text(
            text = "Pair the desktop bridge, verify your earbuds, then speak short developer commands hands-free.",
            style = MaterialTheme.typography.bodyMedium,
            color = DevPodsColor.Muted,
        )

        // Feature cards row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnboardingFeatureCard(
                title = "Pair",
                body = "Scan the bridge QR",
                accentColor = DevPodsColor.Teal,
                modifier = Modifier.weight(1f),
            )
            OnboardingFeatureCard(
                title = "Verify",
                body = "Prove wake and STT",
                accentColor = DevPodsColor.Amber,
                modifier = Modifier.weight(1f),
            )
            OnboardingFeatureCard(
                title = "Approve",
                body = "Risky actions pause",
                accentColor = DevPodsColor.Blue,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Primary CTA
        DevPodsButton(
            text = "Pair your bridge",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            style = ButtonStyle.Primary,
        )

        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Skip for now",
                color = DevPodsColor.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun OnboardingFeatureCard(
    title: String,
    body: String,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    DevPodsCard(
        modifier = modifier,
        accentColor = accentColor,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = DevPodsColor.Ink,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = DevPodsColor.Muted,
            )
        }
    }
}
