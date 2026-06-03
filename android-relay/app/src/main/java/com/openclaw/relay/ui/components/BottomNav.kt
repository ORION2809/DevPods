package com.openclaw.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.DevPodsSpacing

enum class DevPodsTab {
    Home, Activity, Device, Help, Dev,
}

private data class TabConfig(
    val tab: DevPodsTab,
    val label: String,
)

@Composable
fun BottomNav(
    selectedTab: DevPodsTab,
    isDevMode: Boolean,
    onTabSelected: (DevPodsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = buildList {
        add(TabConfig(DevPodsTab.Home, "Home"))
        add(TabConfig(DevPodsTab.Activity, "Activity"))
        add(TabConfig(DevPodsTab.Device, "Device"))
        add(TabConfig(DevPodsTab.Help, "Help"))
        if (isDevMode) {
            add(TabConfig(DevPodsTab.Dev, "Dev"))
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DevPodsSpacing.navHeight)
            .padding(horizontal = DevPodsSpacing.navX, vertical = 12.dp)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(DevPodsSpacing.navX + 12.dp),
                ambientColor = DevPodsColor.Ink.copy(alpha = 0.12f),
                spotColor = DevPodsColor.Ink.copy(alpha = 0.10f),
            )
            .clip(RoundedCornerShape(DevPodsSpacing.navX + 12.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        DevPodsColor.White.copy(alpha = 0.94f),
                        DevPodsColor.Surface.copy(alpha = 0.78f),
                    ),
                ),
            )
            .border(1.dp, DevPodsColor.GlassBorder, RoundedCornerShape(DevPodsSpacing.navX + 12.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { (tab, label) ->
            val isSelected = tab == selectedTab
            val activeBg = if (isDevMode && tab == DevPodsTab.Dev) {
                DevPodsColor.AmberSoft
            } else {
                DevPodsColor.MintSoft
            }
            val activeText = if (isDevMode && tab == DevPodsTab.Dev) {
                DevPodsColor.Amber
            } else {
                DevPodsColor.Teal
            }

            Column(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = { onTabSelected(tab) },
                        role = Role.Tab,
                    )
                    .semantics {
                        contentDescription = "$label tab"
                        role = Role.Tab
                        selected = isSelected
                    }
                    .background(if (isSelected) activeBg else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Dot indicator (reference style: visible for inactive, implied by pill for active)
                if (!isSelected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(DevPodsColor.Muted),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
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
