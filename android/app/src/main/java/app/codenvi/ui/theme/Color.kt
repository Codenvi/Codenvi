package app.codenvi.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Shared design tokens (docs/design.md §0.7). Keep in sync with desktop/src/Codenvi.Desktop/Themes/Dark.xaml.
object Tokens {
    val Primary = Color(0xFFFF8A1F)
    val PrimaryHover = Color(0xFFFFA24D)
    val PrimaryPressed = Color(0xFFE07410)
    val OnPrimary = Color(0xFF1A1000)
    val PrimaryContainer = Color(0xFF4A2A0A)
    val OnPrimaryContainer = Color(0xFFFFD9B8)
    val Background = Color(0xFF121212)
    val Surface = Color(0xFF1C1C1E)
    val SurfaceVariant = Color(0xFF2A2A2D)
    val Outline = Color(0xFF3A3A3D)
    val OnSurface = Color(0xFFEDEDED)
    val OnSurfaceMuted = Color(0xFFA0A0A5)
    val Success = Color(0xFF4CAF50)
    val Warning = Color(0xFFFFC107)
    val Error = Color(0xFFEF5350)
}

val CodenviDarkColors = darkColorScheme(
    primary = Tokens.Primary,
    onPrimary = Tokens.OnPrimary,
    primaryContainer = Tokens.PrimaryContainer,
    onPrimaryContainer = Tokens.OnPrimaryContainer,
    secondary = Tokens.Primary,
    onSecondary = Tokens.OnPrimary,
    secondaryContainer = Tokens.PrimaryContainer,
    onSecondaryContainer = Tokens.OnPrimaryContainer,
    background = Tokens.Background,
    onBackground = Tokens.OnSurface,
    surface = Tokens.Surface,
    onSurface = Tokens.OnSurface,
    surfaceVariant = Tokens.SurfaceVariant,
    onSurfaceVariant = Tokens.OnSurfaceMuted,
    surfaceContainer = Tokens.Surface,
    surfaceContainerHigh = Tokens.SurfaceVariant,
    surfaceContainerHighest = Tokens.SurfaceVariant,
    surfaceContainerLow = Tokens.Surface,
    surfaceContainerLowest = Tokens.Background,
    outline = Tokens.Outline,
    outlineVariant = Tokens.Outline,
    error = Tokens.Error,
    onError = Color.Black,
)
