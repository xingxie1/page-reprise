package app.pagereprise

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

private val graphite = Color(0xFF191D20)
private val panel = Color(0xFF252B2D)
private val raised = Color(0xFF2E3536)
private val chalk = Color(0xFFF3F3E8)
private val lime = Color(0xFFD7EF74)
internal val launcherBackground = Color(0xFF191D23)

internal val graphiteColors = darkColorScheme(
    primary = lime,
    onPrimary = Color(0xFF20241C),
    primaryContainer = Color(0xFF2C3434),
    onPrimaryContainer = Color(0xFFF5F6EA),
    inversePrimary = Color(0xFF4C6016),
    secondary = Color(0xFFC6CDBE),
    onSecondary = Color(0xFF242B25),
    secondaryContainer = Color(0xFF3C4940),
    onSecondaryContainer = Color(0xFFE5E8DB),
    tertiary = Color(0xFFF0C68A),
    onTertiary = Color(0xFF3B2C16),
    tertiaryContainer = Color(0xFF534128),
    onTertiaryContainer = Color(0xFFFFE1B4),
    background = graphite,
    onBackground = chalk,
    surface = panel,
    onSurface = chalk,
    surfaceVariant = raised,
    onSurfaceVariant = Color(0xFFBEC6BF),
    outline = Color(0xFF83988B),
    outlineVariant = Color(0xFF56615F),
    inverseSurface = Color(0xFFE1E6DC),
    inverseOnSurface = Color(0xFF252B27),
    surfaceTint = lime,
    surfaceDim = graphite,
    surfaceBright = Color(0xFF404749),
    surfaceContainerLowest = Color(0xFF141719),
    surfaceContainerLow = Color(0xFF202628),
    surfaceContainer = panel,
    surfaceContainerHigh = raised,
    surfaceContainerHighest = Color(0xFF363E40)
)
