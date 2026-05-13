package com.openclaw.relay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = DevPodsColor.Teal,
    onPrimary = DevPodsColor.White,
    primaryContainer = DevPodsColor.TealSoft,
    onPrimaryContainer = DevPodsColor.Teal,
    secondary = DevPodsColor.Amber,
    onSecondary = DevPodsColor.White,
    secondaryContainer = DevPodsColor.AmberSoft,
    onSecondaryContainer = DevPodsColor.Amber,
    tertiary = DevPodsColor.Blue,
    onTertiary = DevPodsColor.White,
    tertiaryContainer = DevPodsColor.BlueSoft,
    onTertiaryContainer = DevPodsColor.Blue,
    error = DevPodsColor.Red,
    onError = DevPodsColor.White,
    errorContainer = DevPodsColor.RedSoft,
    onErrorContainer = DevPodsColor.Red,
    background = DevPodsColor.Background,
    onBackground = DevPodsColor.Ink,
    surface = DevPodsColor.Surface,
    onSurface = DevPodsColor.Ink,
    surfaceVariant = DevPodsColor.Surface2,
    onSurfaceVariant = DevPodsColor.Muted,
    outline = DevPodsColor.Line,
    outlineVariant = DevPodsColor.Line,
    scrim = DevPodsColor.Ink.copy(alpha = 0.32f),
    inverseSurface = DevPodsColor.Ink,
    inverseOnSurface = DevPodsColor.Surface,
    inversePrimary = DevPodsColor.Mint,
)

private val DarkColorScheme = darkColorScheme(
    primary = DevPodsColor.Mint,
    onPrimary = DevPodsColor.Ink,
    primaryContainer = DevPodsColor.Teal.copy(alpha = 0.25f),
    onPrimaryContainer = DevPodsColor.Mint,
    secondary = DevPodsColor.AmberSoft,
    onSecondary = DevPodsColor.Ink,
    secondaryContainer = DevPodsColor.Amber.copy(alpha = 0.25f),
    onSecondaryContainer = DevPodsColor.AmberSoft,
    tertiary = DevPodsColor.BlueSoft,
    onTertiary = DevPodsColor.Ink,
    tertiaryContainer = DevPodsColor.Blue.copy(alpha = 0.25f),
    onTertiaryContainer = DevPodsColor.BlueSoft,
    error = DevPodsColor.RedSoft,
    onError = DevPodsColor.Ink,
    errorContainer = DevPodsColor.Red.copy(alpha = 0.25f),
    onErrorContainer = DevPodsColor.RedSoft,
    background = DevPodsColor.Ink,
    onBackground = DevPodsColor.Surface,
    surface = DevPodsColor.Ink2,
    onSurface = DevPodsColor.Surface,
    surfaceVariant = DevPodsColor.Ink.copy(alpha = 0.5f),
    onSurfaceVariant = DevPodsColor.Muted,
    outline = DevPodsColor.Muted.copy(alpha = 0.5f),
    outlineVariant = DevPodsColor.Muted.copy(alpha = 0.3f),
    scrim = DevPodsColor.Ink.copy(alpha = 0.5f),
    inverseSurface = DevPodsColor.Surface,
    inverseOnSurface = DevPodsColor.Ink,
    inversePrimary = DevPodsColor.Teal,
)

@Composable
fun DevPodsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DevPodsTypography,
        shapes = DevPodsShapes,
        content = content,
    )
}
