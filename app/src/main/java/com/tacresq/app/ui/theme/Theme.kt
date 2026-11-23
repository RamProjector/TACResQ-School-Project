package com.tacresq.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

// TACResQ's mobile palette follows the existing web identity: deep ink,
// warm ember, and restrained sage. Paired foregrounds are selected for AA contrast.
private val TacruLightColors = lightColorScheme(
    primary = Color(0xFFC6431F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBECE8),
    onPrimaryContainer = Color(0xFF7F2C1C),
    secondary = Color(0xFF28735D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9F3ED),
    onSecondaryContainer = Color(0xFF194B3C),
    tertiary = Color(0xFF8A5200),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF2DE),
    onTertiaryContainer = Color(0xFF6A3B00),
    error = Color(0xFFB74131),
    onError = Color.White,
    errorContainer = Color(0xFFFBECE8),
    onErrorContainer = Color(0xFF7F2C1C),
    background = Color(0xFFF0F1EB),
    onBackground = Color(0xFF142A31),
    surface = Color(0xFFFFFEFA),
    onSurface = Color(0xFF1C3035),
    surfaceVariant = Color(0xFFE9ECE5),
    onSurfaceVariant = Color(0xFF526367),
    outline = Color(0xFF778A83),
    outlineVariant = Color(0xFFD6DFD8),
)

private val TacruDarkColors = darkColorScheme(
    primary = Color(0xFFFF9A7C),
    onPrimary = Color(0xFF3A0D03),
    primaryContainer = Color(0xFF6D2D1F),
    onPrimaryContainer = Color(0xFFFFDAD0),
    secondary = Color(0xFF91B7A2),
    onSecondary = Color(0xFF0E3024),
    secondaryContainer = Color(0xFF244C3B),
    onSecondaryContainer = Color(0xFFC9E7D4),
    tertiary = Color(0xFFF1BC76),
    onTertiary = Color(0xFF3F2600),
    tertiaryContainer = Color(0xFF5B3A13),
    onTertiaryContainer = Color(0xFFFFE2B7),
    error = Color(0xFFFFB4A8),
    onError = Color(0xFF5F1006),
    errorContainer = Color(0xFF76271C),
    onErrorContainer = Color(0xFFFFDAD2),
    background = Color(0xFF142A31),
    onBackground = Color(0xFFF3F4ED),
    surface = Color(0xFF1C3035),
    onSurface = Color(0xFFF3F4ED),
    surfaceVariant = Color(0xFF294249),
    onSurfaceVariant = Color(0xFFB7C9C2),
    outline = Color(0xFF829A90),
    outlineVariant = Color(0xFF3A5555),
)

private val TacruTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(
            fontSize = 26.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.5).sp,
            fontWeight = FontWeight.Bold,
        ),
        titleLarge = base.titleLarge.copy(
            fontSize = 21.sp,
            lineHeight = 27.sp,
            letterSpacing = (-0.35).sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = base.titleMedium.copy(
            fontSize = 17.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 23.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodySmall = base.bodySmall.copy(
            fontSize = 12.sp,
            lineHeight = 17.sp,
        ),
        labelLarge = base.labelLarge.copy(
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        labelMedium = base.labelMedium.copy(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

private val TacruShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun TACResQTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> darkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (isDark) TacruDarkColors else TacruLightColors,
        typography = TacruTypography,
        shapes = TacruShapes,
        content = content,
    )
}
