package com.dazzdroid.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFB347)
val Ink = Color(0xFF0E0D0C)
val Panel = Color(0xFF1A1816)
val Muted = Color(0xFF8A847C)
val RecRed = Color(0xFFE53935)

@Composable
fun DazzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            onPrimary = Color.Black,
            secondary = Color(0xFFE8DCC8),
            background = Ink,
            surface = Panel,
            onSurface = Color(0xFFF2EDE4),
            surfaceVariant = Color(0xFF2A2724),
        ),
        content = content,
    )
}
