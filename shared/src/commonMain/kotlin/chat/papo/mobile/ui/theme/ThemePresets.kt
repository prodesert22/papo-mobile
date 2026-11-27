package chat.papo.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.theme_preset_amber
import chat.papo.mobile.resources.theme_preset_amethyst
import chat.papo.mobile.resources.theme_preset_forest
import chat.papo.mobile.resources.theme_preset_ocean
import chat.papo.mobile.resources.theme_preset_papo
import chat.papo.mobile.resources.theme_preset_rose
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot
import org.jetbrains.compose.resources.StringResource

/**
 * Uma predefinição de tema. A do Papo é o esquema do app, escrito à mão em `Theme.kt`; as outras nascem de uma cor
 * [seed] (ARGB) pelo esquema "tonal spot" do Material, que devolve o conjunto completo de papéis para claro e escuro.
 */
class ThemePreset internal constructor(val id: String, val name: StringResource, private val seed: Int?) {
    /** O tema desta predefinição no [variant]; o OLED é o escuro com os fundos pretos. */
    fun theme(variant: ThemeVariant): ResolvedTheme = when (variant) {
        ThemeVariant.Light -> base(dark = false)
        ThemeVariant.Dark -> base(dark = true)
        ThemeVariant.Oled -> oled(base(dark = true))
    }

    private fun base(dark: Boolean): ResolvedTheme =
        if (seed == null) ResolvedTheme(if (dark) DarkColors else LightColors, if (dark) DarkPapoColors else LightPapoColors, dark)
        else seeded(seed, dark)
}

object ThemePresets {
    val Papo = ThemePreset("papo", Res.string.theme_preset_papo, null)
    private val Ocean = ThemePreset("ocean", Res.string.theme_preset_ocean, 0xFF1E78C8.toInt())
    private val Forest = ThemePreset("forest", Res.string.theme_preset_forest, 0xFF2E7D4F.toInt())
    private val Amethyst = ThemePreset("amethyst", Res.string.theme_preset_amethyst, 0xFF7A52C7.toInt())
    private val Amber = ThemePreset("amber", Res.string.theme_preset_amber, 0xFFE09A00.toInt())
    private val Rose = ThemePreset("rose", Res.string.theme_preset_rose, 0xFFD6336C.toInt())

    val all: List<ThemePreset> = listOf(Papo, Ocean, Forest, Amethyst, Amber, Rose)

    /** A predefinição de [id]; um id que este código não conhece (gravado por outra versão) cai no Papo. */
    fun find(id: String): ThemePreset = all.firstOrNull { it.id == id } ?: Papo
}

private val OledOutline = Color(0xFF6B6B6B)
private val OledOutlineVariant = Color(0xFF2C2C2C)

/**
 * O escuro com fundo, superfícies, áreas de itens e faixa de servidores em `#000000`, que num painel OLED apagam os pontos.
 * Sem tons de fundo para separar as áreas, quem separa são os contornos, por isso são mais claros que no escuro comum.
 */
private fun oled(dark: ResolvedTheme): ResolvedTheme {
    val black = Color.Black
    val scheme = dark.colorScheme.copy(
        background = black,
        surface = black,
        surfaceDim = black,
        surfaceVariant = black,
        surfaceContainerLowest = black,
        surfaceContainerLow = black,
        surfaceContainer = black,
        surfaceContainerHigh = black,
        surfaceContainerHighest = black,
        outline = OledOutline,
        outlineVariant = OledOutlineVariant,
    )
    return ResolvedTheme(scheme, dark.papo.copy(rail = black), dark = true)
}

private fun seeded(seed: Int, dark: Boolean): ResolvedTheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)
    val roles = MaterialDynamicColors()
    fun DynamicColor.color() = Color(getArgb(scheme))
    val colors = colorSchemeOf(scheme, roles)
    val accent = Color(scheme.primaryPalette.tone(if (dark) 75 else 50))
    val papo = if (dark) {
        DarkPapoColors.copy(
            rail = roles.surfaceContainerLowest().color(),
            accent = accent,
            onAccent = bestOn(accent),
        )
    } else {
        LightPapoColors.copy(
            rail = Color(scheme.primaryPalette.tone(20)),
            onRail = Color(scheme.neutralPalette.tone(95)),
            onRailMuted = Color(scheme.neutralVariantPalette.tone(75)),
            accent = accent,
            onAccent = bestOn(accent),
            unreadName = colors.onBackground,
        )
    }
    return ResolvedTheme(colors, papo, dark)
}

/** Todos os papéis vêm do esquema dinâmico (claro ou escuro), então o construtor usado não muda nada: nenhum padrão sobra. */
private fun colorSchemeOf(scheme: DynamicScheme, roles: MaterialDynamicColors): ColorScheme {
    fun DynamicColor.color() = Color(getArgb(scheme))
    return lightColorScheme(
        primary = roles.primary().color(),
        onPrimary = roles.onPrimary().color(),
        primaryContainer = roles.primaryContainer().color(),
        onPrimaryContainer = roles.onPrimaryContainer().color(),
        inversePrimary = roles.inversePrimary().color(),
        secondary = roles.secondary().color(),
        onSecondary = roles.onSecondary().color(),
        secondaryContainer = roles.secondaryContainer().color(),
        onSecondaryContainer = roles.onSecondaryContainer().color(),
        tertiary = roles.tertiary().color(),
        onTertiary = roles.onTertiary().color(),
        tertiaryContainer = roles.tertiaryContainer().color(),
        onTertiaryContainer = roles.onTertiaryContainer().color(),
        background = roles.background().color(),
        onBackground = roles.onBackground().color(),
        surface = roles.surface().color(),
        onSurface = roles.onSurface().color(),
        surfaceVariant = roles.surfaceVariant().color(),
        onSurfaceVariant = roles.onSurfaceVariant().color(),
        surfaceTint = roles.surfaceTint().color(),
        inverseSurface = roles.inverseSurface().color(),
        inverseOnSurface = roles.inverseOnSurface().color(),
        error = roles.error().color(),
        onError = roles.onError().color(),
        errorContainer = roles.errorContainer().color(),
        onErrorContainer = roles.onErrorContainer().color(),
        outline = roles.outline().color(),
        outlineVariant = roles.outlineVariant().color(),
        scrim = roles.scrim().color(),
        surfaceBright = roles.surfaceBright().color(),
        surfaceDim = roles.surfaceDim().color(),
        surfaceContainer = roles.surfaceContainer().color(),
        surfaceContainerHigh = roles.surfaceContainerHigh().color(),
        surfaceContainerHighest = roles.surfaceContainerHighest().color(),
        surfaceContainerLow = roles.surfaceContainerLow().color(),
        surfaceContainerLowest = roles.surfaceContainerLowest().color(),
    )
}
