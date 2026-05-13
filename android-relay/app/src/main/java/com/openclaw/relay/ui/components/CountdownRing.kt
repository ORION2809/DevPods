package com.openclaw.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.theme.DevPodsColor

@Composable
fun CountdownRing(
    seconds: Int,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(72.dp)
            .drawBehind {
                val strokeWidth = 5.dp.toPx()
                val startAngle = -90f
                val sweepAngle = 360f * progress.coerceIn(0f, 1f)

                drawArc(
                    color = DevPodsColor.Ink.copy(alpha = 0.06f),
                    startAngle = startAngle,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                drawArc(
                    color = DevPodsColor.Teal,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
            .background(Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${seconds}s",
            style = MaterialTheme.typography.titleLarge,
            color = DevPodsColor.Teal,
        )
    }
}
