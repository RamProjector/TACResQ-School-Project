package com.tacresq.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val TacruLightColors = lightColorScheme(
    primary = Color(0xFF0B4F6C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1EAF6),
    onPrimaryContainer = Color(0xFF062B3B),
    secondary = Color(0xFF177E89),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC5F2F1),
    onSecondaryContainer = Color(0xFF063B40),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB7),
    onTertiaryContainer = Color(0xFF3B1F00),
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7EEF2),
    onSurface = Color(0xFF17232B),
    onSurfaceVariant = Color(0xFF53636D),
)

private val TacruDarkColors = darkColorScheme(
    primary = Color(0xFF8FD5F2),
    onPrimary = Color(0xFF003549),
    primaryContainer = Color(0xFF07506D),
    onPrimaryContainer = Color(0xFFD1EAF6),
    secondary = Color(0xFF83D5D6),
    onSecondary = Color(0xFF003739),
    secondaryContainer = Color(0xFF125E61),
    onSecondaryContainer = Color(0xFFC5F2F1),
    tertiary = Color(0xFFFFB979),
    onTertiary = Color(0xFF522900),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF10181D),
    surface = Color(0xFF10181D),
    surfaceVariant = Color(0xFF3D4A51),
    onSurface = Color(0xFFE0E9ED),
    onSurfaceVariant = Color(0xFFBEC9CE),
)

private val TacruShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun TACResQTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TacruDarkColors else TacruLightColors,
        shapes = TacruShapes,
        content = content,
    )
}
