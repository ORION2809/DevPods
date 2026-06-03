package com.openclaw.relay.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.openclaw.relay.R

@Composable
fun DevPodsWordmarkLight(
    modifier: Modifier = Modifier,
    contentDescription: String = "DevPods wordmark",
) {
    Image(
        painter = painterResource(id = R.drawable.devpods_wordmark_light),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun DevPodsAppIconTile(
    modifier: Modifier = Modifier,
    contentDescription: String = "DevPods app icon",
) {
    Image(
        painter = painterResource(id = R.drawable.devpods_app_icon_tile),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun DevPodsMarkTealDark(
    modifier: Modifier = Modifier,
    contentDescription: String = "DevPods brand mark",
) {
    Image(
        painter = painterResource(id = R.drawable.devpods_mark_teal_dark),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
