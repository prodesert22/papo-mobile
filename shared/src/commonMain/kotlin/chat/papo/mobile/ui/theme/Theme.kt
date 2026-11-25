package chat.papo.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.bricolage_grotesque_500
import chat.papo.mobile.resources.bricolage_grotesque_700
import org.jetbrains.compose.resources.Font

// Petróleo (fundo), manga (ação e não lidas) e água (presença).
private val Manga = Color(0xFFFFB238)
private val Agua = Color(0xFF5CC8BE)

// Dourado do cliente web (`--gold`), do destaque das mensagens que mencionam ou respondem a quem usa a conta.
private val Ouro = Color(0xFFE7A80B)

private val DarkColors = darkColorScheme(
    primary = Manga,
    onPrimary = Color(0xFF2B1B00),
    primaryContainer = Color(0xFF5C3D00),
    onPrimaryContainer = Color(0xFFFFDDA8),
    secondary = Agua,
    onSecondary = Color(0xFF00332F),
    secondaryContainer = Color(0xFF17403D),
    onSecondaryContainer = Color(0xFFBDEDE7),
    tertiary = Color(0xFF9CC4FF),
    onTertiary = Color(0xFF05305F),
    background = Color(0xFF0F1E24),
    onBackground = Color(0xFFEAF2F1),
    surface = Color(0xFF0F1E24),
    onSurface = Color(0xFFEAF2F1),
    surfaceVariant = Color(0xFF1D343C),
    onSurfaceVariant = Color(0xFF9DB3B5),
    surfaceContainerLowest = Color(0xFF0A161B),
    surfaceContainerLow = Color(0xFF13252C),
    surfaceContainer = Color(0xFF172A31),
    surfaceContainerHigh = Color(0xFF1D343C),
    surfaceContainerHighest = Color(0xFF25414A),
    outline = Color(0xFF4B6368),
    outlineVariant = Color(0xFF2A4249),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF47100C),
    errorContainer = Color(0xFF5C1F1A),
    onErrorContainer = Color(0xFFFFDAD5),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F4C5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8EE),
    onPrimaryContainer = Color(0xFF05303B),
    secondary = Color(0xFF1B7F76),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9EEE9),
    onSecondaryContainer = Color(0xFF00332F),
    tertiary = Color(0xFF8A5300),
    onTertiary = Color.White,
    background = Color(0xFFF2F6F5),
    onBackground = Color(0xFF12262B),
    surface = Color(0xFFF2F6F5),
    onSurface = Color(0xFF12262B),
    surfaceVariant = Color(0xFFE1EAE8),
    onSurfaceVariant = Color(0xFF4C6266),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFCFB),
    surfaceContainer = Color(0xFFE9F0EE),
    surfaceContainerHigh = Color(0xFFE1EAE8),
    surfaceContainerHighest = Color(0xFFD8E3E1),
    outline = Color(0xFF7A9094),
    outlineVariant = Color(0xFFC3D2D0),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/**
 * Cores fora do esquema do Material: o trilho de contas é escuro nos dois temas. [highlight] é o fundo
 * translúcido da mensagem dirigida a quem usa a conta e [highlightBar] a barra no começo da linha.
 */
@Immutable
data class PapoColors(
    val rail: Color,
    val onRail: Color,
    val onRailMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val online: Color,
    val highlight: Color,
    val highlightBar: Color,
)

private val DarkPapoColors = PapoColors(
    rail = Color(0xFF0A161B),
    onRail = Color(0xFFEAF2F1),
    onRailMuted = Color(0xFF7F979A),
    accent = Manga,
    onAccent = Color(0xFF2B1B00),
    online = Agua,
    highlight = Ouro.copy(alpha = 0.18f),
    highlightBar = Ouro,
)

private val LightPapoColors = DarkPapoColors.copy(
    rail = Color(0xFF0F3A46),
    onRailMuted = Color(0xFF9DBFC6),
    highlight = Ouro.copy(alpha = 0.16f),
    highlightBar = Ouro,
)

val LocalPapoColors = staticCompositionLocalOf { DarkPapoColors }

@Composable
private fun papoTypography(): Typography {
    val display = FontFamily(
        Font(Res.font.bricolage_grotesque_500, FontWeight.Medium),
        Font(Res.font.bricolage_grotesque_700, FontWeight.Bold),
    )
    val base = Typography()
    fun TextStyle.display(weight: FontWeight, tracking: Double = 0.0) =
        copy(fontFamily = display, fontWeight = weight, letterSpacing = tracking.sp)
    return base.copy(
        displaySmall = base.displaySmall.display(FontWeight.Bold, -0.5),
        headlineLarge = base.headlineLarge.display(FontWeight.Bold, -0.4),
        headlineMedium = base.headlineMedium.display(FontWeight.Bold, -0.3),
        headlineSmall = base.headlineSmall.display(FontWeight.Bold, -0.2),
        titleLarge = base.titleLarge.display(FontWeight.Bold),
        titleMedium = base.titleMedium.display(FontWeight.Medium, 0.0),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
    )
}

@Composable
fun PapoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPapoColors provides if (dark) DarkPapoColors else LightPapoColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = papoTypography(),
            content = content,
        )
    }
}
