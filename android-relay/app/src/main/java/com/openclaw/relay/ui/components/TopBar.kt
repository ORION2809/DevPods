package com.openclaw.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.PillShape

@Composable
fun TopBar(
    isDevMode: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "DevPods",
            style = MaterialTheme.typography.headlineSmall,
            color = DevPodsColor.Ink,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        ModeBadge(isDevMode = isDevMode)
    }
}

@Composable
private fun ModeBadge(isDevMode: Boolean) {
    val bg = if (isDevMode) DevPodsColor.AmberSoft else DevPodsColor.TealSoft
    val text = if (isDevMode) "Developer mode" else "Standard mode"
    val color = if (isDevMode) DevPodsColor.Amber else DevPodsColor.Teal

    Box(
        modifier = Modifier
            .wrapContentWidth()
            .clip(PillShape)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
