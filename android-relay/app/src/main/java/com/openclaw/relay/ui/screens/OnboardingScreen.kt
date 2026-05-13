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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.components.ButtonStyle
import com.openclaw.relay.ui.components.DevPodsButton
import com.openclaw.relay.ui.components.DevPodsCard
import com.openclaw.relay.ui.components.Waveform
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.DevPodsShapes

@Composable
fun OnboardingScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Title
        Text(
            text = "DevPods",
            style = MaterialTheme.typography.headlineLarge,
            color = DevPodsColor.Ink,
        )

        // Product promise
        Text(
            text = "Hands-free developer controls through your earbuds.",
            style = MaterialTheme.typography.bodyLarge,
            color = DevPodsColor.Muted,
        )

        // Hero visual - two earbuds flanking waveform
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(DevPodsShapes.extraLarge)
                .background(DevPodsColor.DarkPanel),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left earbud
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 72.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(DevPodsColor.Surface),
                )
                // Waveform
                Waveform(
                    modifier = Modifier.width(140.dp),
                    isAnimating = true,
                )
                // Right earbud
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 72.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(DevPodsColor.Surface),
                )
            }
        }

        // Hero title
        Text(
            text = "Talk to your workspace through ordinary earbuds.",
            style = MaterialTheme.typography.headlineSmall,
            color = DevPodsColor.Ink,
        )

        // Subtitle
        Text(
            text = "Tap, long-press, or speak to check repo status, run tests, open files, and more \u2014 all without touching your phone or keyboard.",
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

        // Primary button
        DevPodsButton(
            text = "Pair your bridge",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            style = ButtonStyle.Primary,
        )
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
