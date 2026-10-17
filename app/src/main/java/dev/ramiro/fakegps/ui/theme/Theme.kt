package dev.ramiro.fakegps.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Paleta verde suave ("eco"): descansa la vista y combina con el mapa claro. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B7F5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE0),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF4C6359),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEDE5),
    onSecondaryContainer = Color(0xFF082018),
    tertiary = Color(0xFFB26A00),
    onTertiary = Color.White,
    background = Color(0xFFF4F8F5),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFFBFDFB),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFE2EAE5),
    onSurfaceVariant = Color(0xFF55615B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F9F6),
    surfaceContainer = Color(0xFFEFF4F1),
    surfaceContainerHigh = Color(0xFFE9EFEB),
    surfaceContainerHighest = Color(0xFFE3EAE6),
    outline = Color(0xFF7A8781),
    outlineVariant = Color(0xFFCBD5CF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD8B0),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513C),
    onPrimaryContainer = Color(0xFFCDEFE0),
    secondary = Color(0xFFB3CCC0),
    onSecondary = Color(0xFF1F352C),
    secondaryContainer = Color(0xFF354B42),
    onSecondaryContainer = Color(0xFFCFE9DC),
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF462A00),
    background = Color(0xFF0E1311),
    onBackground = Color(0xFFDDE4E0),
    surface = Color(0xFF121816),
    onSurface = Color(0xFFDDE4E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBEC9C3),
    surfaceContainerLowest = Color(0xFF0B100E),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Colores de apoyo que no son parte del esquema de Material. */
object AppColors {
    val Saved = Color(0xFFFFC107)
    val TrafficFree = Color(0xFF2E9D5B)
    val TrafficModerate = Color(0xFFF2A100)
    val TrafficHeavy = Color(0xFFD64541)
    val RedLight = Color(0xFFD64541)
}

@Composable
fun FakeGpsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
