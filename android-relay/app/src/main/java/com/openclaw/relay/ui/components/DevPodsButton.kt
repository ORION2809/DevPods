package com.openclaw.relay.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openclaw.relay.ui.theme.DevPodsColor
import com.openclaw.relay.ui.theme.DevPodsSpacing
import com.openclaw.relay.ui.theme.PillShape

sealed class ButtonStyle(
    val background: Color,
    val contentColor: Color,
    val borderColor: Color? = null,
    val shadowElevation: Dp? = null,
) {
    data object Primary : ButtonStyle(
        background = DevPodsColor.Teal,
        contentColor = DevPodsColor.White,
        borderColor = null,
        shadowElevation = 10.dp,
    )
    data object Secondary : ButtonStyle(
        background = DevPodsColor.Surface,
        contentColor = DevPodsColor.Teal,
        borderColor = DevPodsColor.Line,
        shadowElevation = null,
    )
    data object Danger : ButtonStyle(
        background = DevPodsColor.RedSoft,
        contentColor = DevPodsColor.Red,
        borderColor = DevPodsColor.DangerBorder,
        shadowElevation = null,
    )
    data object Ghost : ButtonStyle(
        background = Color.Transparent,
        contentColor = DevPodsColor.Teal,
        borderColor = null,
        shadowElevation = null,
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "buttonPressScale",
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            !enabled -> DevPodsColor.Surface2
            isPressed -> style.background.copy(alpha = 0.85f)
            else -> style.background
        },
        label = "buttonPressBg",
    )

    val baseModifier = modifier
        .scale(scale)
        .height(DevPodsSpacing.buttonHeight)
        .clip(PillShape)

    val shadowModifier = if (style.shadowElevation != null && enabled) {
        baseModifier.shadow(
            elevation = style.shadowElevation,
            shape = PillShape,
            ambientColor = DevPodsColor.PrimaryButtonShadow,
            spotColor = DevPodsColor.PrimaryButtonShadow,
        )
    } else {
        baseModifier
    }

    val borderModifier = if (style.borderColor != null) {
        shadowModifier.border(1.dp, style.borderColor, PillShape)
    } else {
        shadowModifier
    }

    Box(
        modifier = borderModifier
            .background(bgColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(
                    bounded = true,
                    color = Color.White.copy(alpha = 0.3f),
                ),
                onClick = onClick,
                role = Role.Button,
            )
            .semantics {
                contentDescription = text
                role = Role.Button
            }
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "smallButtonPressScale",
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            !enabled -> DevPodsColor.Surface2
            isPressed -> style.background.copy(alpha = 0.85f)
            else -> style.background
        },
        label = "smallButtonPressBg",
    )

    val baseModifier = modifier
        .scale(scale)
        .heightIn(min = 48.dp)
        .clip(PillShape)

    val borderModifier = if (style.borderColor != null) {
        baseModifier.border(1.dp, style.borderColor, PillShape)
    } else {
        baseModifier
    }

    Box(
        modifier = borderModifier
            .background(bgColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(
                    bounded = true,
                    color = Color.White.copy(alpha = 0.3f),
                ),
                onClick = onClick,
                role = Role.Button,
            )
            .semantics {
                contentDescription = text
                role = Role.Button
            }
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
