package com.cropcast.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CropGreen = Color(0xFF22D477)
val DeepGreen = Color(0xFF0B5537)
val Mint = Color(0xFFD9F7CA)
val ScreenBackground = Color(0xFFF5F9F1)
val CardBorder = Color(0xFFDDE8D7)
val TextPrimary = Color(0xFF153827)
val TextSecondary = Color(0xFF718172)
val Orange = Color(0xFFFF962E)
val Purple = Color(0xFF7D68EE)
val Pink = Color(0xFFF24B9A)
val Yellow = Color(0xFFFFC928)

private val CropCastColors = lightColorScheme(
    primary = CropGreen,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = DeepGreen,
    secondary = DeepGreen,
    background = ScreenBackground,
    surface = Color.White,
    onSurface = TextPrimary,
    outline = CardBorder,
    error = Color(0xFFE45151)
)

private val CropCastDarkColors = darkColorScheme(
    primary = CropGreen,
    onPrimary = Color(0xFF052015),
    primaryContainer = Color(0xFF12452F),
    onPrimaryContainer = Color(0xFFB9F6D2),
    secondary = Color(0xFF8EDDB0),
    background = Color(0xFF0D1711),
    surface = Color(0xFF15221A),
    onSurface = Color(0xFFE2EEE6),
    onSurfaceVariant = Color(0xFFA8B9AD),
    outline = Color(0xFF3A4D40),
    error = Color(0xFFFFB4AB)
)

@Composable
fun CropCastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) CropCastDarkColors else CropCastColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
