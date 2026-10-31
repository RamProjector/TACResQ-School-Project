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

// Shared palette: calm surfaces, strong emergency actions, and consistent status
// colors across every role.
private val TacruLightColors = lightColorScheme(
    primary = Color(0xFF146C94),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEFF7),
    onPrimaryContainer = Color(0xFF0B4F71),
    secondary = Color(0xFF0F8B8D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF3F0),
    onSecondaryContainer = Color(0xFF064E50),
    tertiary = Color(0xFFA65A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF1D6),
    onTertiaryContainer = Color(0xFF6F3D00),
    error = Color(0xFFB42332),
    onError = Color.White,
    errorContainer = Color(0xFFFDE8EA),
    onErrorContainer = Color(0xFF7A1722),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF15243A),
    surface = Color.White,
    onSurface = Color(0xFF15243A),
    surfaceVariant = Color(0xFFEAF0F7),
    onSurfaceVariant = Color(0xFF65758B),
    outline = Color(0xFFB7C5D6),
    outlineVariant = Color(0xFFDCE4EE),
)

private val TacruDarkColors = darkColorScheme(
    primary = Color(0xFF8FD5F2),
    onPrimary = Color(0xFF003549),
    primaryContainer = Color(0xFF07506D),
    onPrimaryContainer = Color(0xFFDCEFF7),
    secondary = Color(0xFF83D5D6),
    onSecondary = Color(0xFF003739),
    secondaryContainer = Color(0xFF125E61),
    onSecondaryContainer = Color(0xFFDDF3F0),
    tertiary = Color(0xFFFFB979),
    onTertiary = Color(0xFF522900),
    tertiaryContainer = Color(0xFF6F3D00),
    onTertiaryContainer = Color(0xFFFFE2B7),
    error = Color(0xFFFFB4B8),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF7A1722),
    onErrorContainer = Color(0xFFFDE8EA),
    background = Color(0xFF101923),
    onBackground = Color(0xFFE4ECF5),
    surface = Color(0xFF101923),
    onSurface = Color(0xFFE4ECF5),
    surfaceVariant = Color(0xFF283746),
    onSurfaceVariant = Color(0xFFB6C4D5),
    outline = Color(0xFF718399),
    outlineVariant = Color(0xFF3B4B5D),
)

// One restrained type scale for every role. The goal is clearer hierarchy,
// not larger text or extra decorative labels.
private val TacruTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
        ),
        titleLarge = base.titleLarge.copy(
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = base.titleMedium.copy(
            fontSize = 16.sp,
            lineHeight = 22.sp,
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
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun TACResQTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TacruDarkColors else TacruLightColors,
        typography = TacruTypography,
        shapes = TacruShapes,
        content = content,
    )
}
