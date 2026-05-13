package com.openclaw.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.theme.DevPodsColor

enum class DevPodsTab {
    Home, Activity, Device, Help, Dev,
}

@Composable
fun BottomNav(
    selectedTab: DevPodsTab,
    isDevMode: Boolean,
    onTabSelected: (DevPodsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = buildList {
        add(DevPodsTab.Home to "Home")
        add(DevPodsTab.Activity to "Activity")
        add(DevPodsTab.Device to "Device")
        add(DevPodsTab.Help to "Help")
        if (isDevMode) {
            add(DevPodsTab.Dev to "Dev")
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = DevPodsColor.Ink.copy(alpha = 0.12f),
                spotColor = DevPodsColor.Ink.copy(alpha = 0.10f),
            )
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        DevPodsColor.White.copy(alpha = 0.94f),
                        DevPodsColor.Surface.copy(alpha = 0.78f),
                    ),
                ),
            )
            .border(1.dp, DevPodsColor.White.copy(alpha = 0.72f), RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { (tab, label) ->
            val isSelected = tab == selectedTab
            val activeBg = if (isDevMode && tab == DevPodsTab.Dev) DevPodsColor.AmberSoft else DevPodsColor.TealSoft
            val activeText = if (isDevMode && tab == DevPodsTab.Dev) DevPodsColor.Amber else DevPodsColor.Ink

            Column(
                modifier = Modifier
                    .wrapContentWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { onTabSelected(tab) }
                    .background(if (isSelected) activeBg else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) {
                                if (isDevMode && tab == DevPodsTab.Dev) DevPodsColor.Amber else DevPodsColor.Ink
                            } else {
                                DevPodsColor.Muted.copy(alpha = 0.5f)
                            }
                        ),
                )
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) activeText else DevPodsColor.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
