package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TitanDarkColorScheme = darkColorScheme(
    primary = AnimeCrimson,
    onPrimary = Color.White,
    primaryContainer = AnimeCrimsonContainer,
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = AnimeCrimsonLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3B1518),
    onSecondaryContainer = Color(0xFFFFDAD8),
    tertiary = Color(0xFFFF8A80),
    onTertiary = Color(0xFF3E0007),
    background = DarkCharcoal,
    onBackground = LightText,
    surface = DarkSurface,
    onSurface = LightText,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = MediumText,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = Color(0xFFCF6679),
    onError = Color.Black
)

@Composable
fun TitanAnimeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TitanDarkColorScheme,
        typography = Typography,
        content = content
    )
}
