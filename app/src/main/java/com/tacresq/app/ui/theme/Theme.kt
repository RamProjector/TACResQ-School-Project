package com.tacresq.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

// ── Extended color palette ──────────────────────────────────────────────────
// Semantic colours that sit outside M3's built-in slots, used for emergency
// urgency, success confirmation, and richer surface depth.

@Immutable
data class TacruExtendedColors(
    /** High-urgency background for SOS / emergency actions. */
    val emergencyContainer: Color,
    val onEmergencyContainer: Color,
    /** Success confirmation surfaces (resolved, complete, sent). */
    val successContainer: Color,
    val onSuccessContainer: Color,
    /** Subtle tinted surfaces for cards that sit above the base background. */
    val surfaceTinted: Color,
    /** Warm-neutral supporting text — slightly more visible than onSurfaceVariant. */
    val supportingText: Color,
)

private val LightExtendedColors = TacruExtendedColors(
    emergencyContainer = Color(0xFFFDE8EA),
    onEmergencyContainer = Color(0xFF7A1722),
    successContainer = Color(0xFFE6F5EC),
    onSuccessContainer = Color(0xFF1B5E32),
    surfaceTinted = Color(0xFFF0F5FA),
    supportingText = Color(0xFF526070),
)

private val DarkExtendedColors = TacruExtendedColors(
    emergencyContainer = Color(0xFF5C1520),
    onEmergencyContainer = Color(0xFFFFD0D4),
    successContainer = Color(0xFF183D28),
    onSuccessContainer = Color(0xFFA5D6BA),
    surfaceTinted = Color(0xFF1A2530),
    supportingText = Color(0xFF9CAAB8),
)

val LocalTacruColors = staticCompositionLocalOf { LightExtendedColors }

/** Access the extended palette via `TacruTheme.extendedColors`. */
object TacruTheme {
    val extendedColors: TacruExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTacruColors.current
}

// ── Core Material 3 colour schemes ──────────────────────────────────────────

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
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFE),
    surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE8EEF5),
    surfaceContainerHighest = Color(0xFFDFE6EF),
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
    surfaceContainerLowest = Color(0xFF0A1119),
    surfaceContainerLow = Color(0xFF141E28),
    surfaceContainer = Color(0xFF1A2530),
    surfaceContainerHigh = Color(0xFF222F3B),
    surfaceContainerHighest = Color(0xFF2C3A47),
    outline = Color(0xFF718399),
    outlineVariant = Color(0xFF3B4B5D),
)

// ── Typography ──────────────────────────────────────────────────────────────

private val TacruTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontSize = 22.sp,
            lineHeight = 28.sp,
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
            lineHeight = 24.sp,
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

// ── Shapes ──────────────────────────────────────────────────────────────────

private val TacruShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// ── Theme entry point ───────────────────────────────────────────────────────

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
    val extendedColors = if (isDark) DarkExtendedColors else LightExtendedColors
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTacruColors provides extendedColors,
    ) {
        MaterialTheme(
            colorScheme = if (isDark) TacruDarkColors else TacruLightColors,
            typography = TacruTypography,
            shapes = TacruShapes,
            content = content,
        )
    }
}
