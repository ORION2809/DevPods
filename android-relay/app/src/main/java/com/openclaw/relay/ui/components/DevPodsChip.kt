package com.openclaw.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.PillShape

sealed class ChipStyle(
    val background: Color,
    val contentColor: Color,
    val dotColor: Color,
) {
    data object Success : ChipStyle(
        background = DevPodsColor.TealSoft,
        contentColor = DevPodsColor.Teal,
        dotColor = DevPodsColor.Mint,
    )
    data object Warning : ChipStyle(
        background = DevPodsColor.AmberSoft,
        contentColor = DevPodsColor.Amber,
        dotColor = DevPodsColor.Amber,
    )
    data object Error : ChipStyle(
        background = DevPodsColor.RedSoft,
        contentColor = DevPodsColor.Red,
        dotColor = DevPodsColor.Red,
    )
    data object Info : ChipStyle(
        background = DevPodsColor.BlueSoft,
        contentColor = DevPodsColor.Blue,
        dotColor = DevPodsColor.Blue,
    )
    data object Muted : ChipStyle(
        background = DevPodsColor.Surface2,
        contentColor = DevPodsColor.Muted,
        dotColor = DevPodsColor.Muted,
    )
}

@Composable
fun DevPodsChip(
    text: String,
    modifier: Modifier = Modifier,
    style: ChipStyle = ChipStyle.Success,
    showDot: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(style.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(style.dotColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = style.contentColor,
        )
    }
}
