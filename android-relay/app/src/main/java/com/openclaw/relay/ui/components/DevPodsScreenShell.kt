package com.openclaw.relay.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.DevPodsSpacing

/**
 * Shared product shell used by every full-screen surface:
 * onboarding, setup, Home, Activity, Device, Help, and Dev.
 *
 * Provides the reference background blobs, topbar, optional note slot,
 * and scaffold insets. Screens apply their own horizontal padding.
 * The caller supplies the bottom bar (primary nav for main screens,
 * nothing for first-run screens).
 */
@Composable
fun DevPodsScreenShell(
    isDevMode: Boolean,
    modifier: Modifier = Modifier,
    note: String? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    DevPodsBackground {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = { TopBar(isDevMode = isDevMode) },
            bottomBar = bottomBar,
        ) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
                // Optional note/support line under the topbar
                if (!note.isNullOrBlank()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DevPodsColor.Muted,
                        modifier = Modifier.padding(
                            horizontal = DevPodsSpacing.screenX,
                            vertical = 4.dp,
                        ),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    content(innerPadding)
                }
            }
        }
    }
}
