package com.scheduleassistant.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = TextLight,
    primaryContainer = CardElevated,
    onPrimaryContainer = TextLight,
    secondary = Accent,
    onSecondary = TextLight,
    secondaryContainer = CardBg,
    onSecondaryContainer = TextLight,
    background = DarkBg,
    onBackground = TextLight,
    surface = SurfaceBg,
    onSurface = TextLight,
    surfaceVariant = CardBg,
    onSurfaceVariant = TextMuted,
    outline = CardBorder,
    outlineVariant = CardBorderSubtle,
    error = Danger,
    onError = TextLight
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun ScheduleAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        shapes = AppShapes,
        content = content
    )
}
