package nl.tinnisoft.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Calm, low-glare palette: deep blue-grey night background, soft teal accent.
private val Night = darkColorScheme(
    primary = Color(0xFF7FCFC4),
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF1E4A45),
    onPrimaryContainer = Color(0xFFB8EDE5),
    secondary = Color(0xFFA9B8D6),
    onSecondary = Color(0xFF13203A),
    secondaryContainer = Color(0xFF2A3650),
    onSecondaryContainer = Color(0xFFD7E2FF),
    tertiary = Color(0xFFE2BE8A),
    background = Color(0xFF0E1218),
    onBackground = Color(0xFFDDE3EA),
    surface = Color(0xFF0E1218),
    onSurface = Color(0xFFDDE3EA),
    surfaceVariant = Color(0xFF1C232C),
    onSurfaceVariant = Color(0xFFB4BEC9),
    surfaceContainer = Color(0xFF161C24),
    surfaceContainerHigh = Color(0xFF1C232C),
    surfaceContainerHighest = Color(0xFF242C36),
    outline = Color(0xFF5A6572),
    error = Color(0xFFF2B8B5),
)

private val Day = lightColorScheme(
    primary = Color(0xFF00695F),
    primaryContainer = Color(0xFFB8EDE5),
    secondary = Color(0xFF4A5A78),
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFF6F8FA),
)

private val BaseTypography = Typography()

/** Slightly larger type than the Material default for easy reading in the dark. */
private val CalmTypography = BaseTypography.copy(
    displaySmall = BaseTypography.displaySmall.copy(fontWeight = FontWeight.Light),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Normal),
    titleLarge = BaseTypography.titleLarge.copy(fontSize = 24.sp),
    titleMedium = BaseTypography.titleMedium.copy(fontSize = 18.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun TinnisoftTheme(light: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (light) Day else Night,
        typography = CalmTypography,
        content = content,
    )
}
