package com.openclaw.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.PillShape

sealed class ButtonStyle(
    val background: Color,
    val contentColor: Color,
) {
    data object Primary : ButtonStyle(
        background = DevPodsColor.Teal,
        contentColor = DevPodsColor.White,
    )
    data object Secondary : ButtonStyle(
        background = DevPodsColor.Surface,
        contentColor = DevPodsColor.Teal,
    )
    data object Danger : ButtonStyle(
        background = DevPodsColor.RedSoft,
        contentColor = DevPodsColor.Red,
    )
    data object Ghost : ButtonStyle(
        background = Color.Transparent,
        contentColor = DevPodsColor.Teal,
    )
}

@Composable
fun DevPodsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .clip(PillShape)
            .background(if (enabled) style.background else DevPodsColor.Surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) style.contentColor else DevPodsColor.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun DevPodsSmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .clip(PillShape)
            .background(if (enabled) style.background else DevPodsColor.Surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) style.contentColor else DevPodsColor.Muted,
            textAlign = TextAlign.Center,
        )
    }
}
