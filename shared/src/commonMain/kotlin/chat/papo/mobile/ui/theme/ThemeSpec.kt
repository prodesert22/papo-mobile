package chat.papo.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.pow

/** Como o app escolhe entre claro e escuro. [Oled] é o escuro com fundo e áreas de itens pretos. */
@Serializable
enum class ThemeMode { System, Light, Dark, Oled }

/** Cada modo tem as próprias cores personalizadas: o que o usuário muda no claro não vale no escuro. */
@Serializable
enum class ThemeVariant { Light, Dark, Oled }

/** As cores que o usuário pode trocar uma a uma. Tudo o mais (textos sobre elas, contornos) sai delas por contraste. */
@Serializable
enum class ThemeToken {
    /** Botões, destaques e o marcador de não lido. */
    Accent,
    Background,

    /** Áreas de itens: cartões, campos, folhas e menus. */
    Surface,

    /** A faixa de servidores. */
    Rail,
    Text,
    TextMuted,

    /** O número de menções. */
    Mention,

    /** O fundo (e a barra) das mensagens que mencionam quem usa a conta. */
    MentionBackground,
}

/**
 * A escolha de tema: o [mode], a predefinição ([preset], o id de [ThemePresets]) e as cores trocadas à mão por modo
 * ([overrides], ARGB; a transparência é ignorada). É o que se grava (JSON) e o que [resolveTheme] transforma em cores.
 */
@Serializable
data class ThemeSpec(
    val mode: ThemeMode = ThemeMode.System,
    val preset: String = ThemePresets.Papo.id,
    val overrides: Map<ThemeVariant, Map<ThemeToken, Int>> = emptyMap(),
) {
    /** O modo que vale agora: [Sistema][ThemeMode.System] segue [systemDark]. */
    fun variant(systemDark: Boolean): ThemeVariant = when (mode) {
        ThemeMode.System -> if (systemDark) ThemeVariant.Dark else ThemeVariant.Light
        ThemeMode.Light -> ThemeVariant.Light
        ThemeMode.Dark -> ThemeVariant.Dark
        ThemeMode.Oled -> ThemeVariant.Oled
    }

    fun withMode(mode: ThemeMode) = copy(mode = mode)

    /** Trocar de predefinição começa de novo: as cores trocadas à mão eram ajustes da anterior. */
    fun withPreset(id: String) = if (id == preset) this else copy(preset = id, overrides = emptyMap())

    /** Troca a cor de [token] no [variant]; [argb] nulo restaura a da predefinição. */
    fun withOverride(variant: ThemeVariant, token: ThemeToken, argb: Int?): ThemeSpec {
        val forVariant = overrides[variant].orEmpty().let { if (argb == null) it - token else it + (token to argb) }
        val all = if (forVariant.isEmpty()) overrides - variant else overrides + (variant to forVariant)
        return copy(overrides = all)
    }

    fun encode(): String = ThemeJson.encodeToString(serializer(), this)

    companion object {
        /** Lê um tema gravado; nulo se não há nada, está estragado ou tem um formato que este código não entende. */
        fun decodeOrNull(raw: String?): ThemeSpec? {
            if (raw.isNullOrBlank()) return null
            return try {
                ThemeJson.decodeFromString(serializer(), raw)
            } catch (e: IllegalArgumentException) {
                null // inclui SerializationException
            }
        }

        /** Como [decodeOrNull], mas o que não se lê vira o tema padrão: um tema ilegível nunca pode impedir o app de abrir. */
        fun decode(raw: String?): ThemeSpec = decodeOrNull(raw) ?: ThemeSpec()
    }
}

private val ThemeJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** O resultado de [resolveTheme]: tudo o que [PapoTheme] precisa. [dark] diz se os ícones das barras do sistema ficam claros. */
class ResolvedTheme(val colorScheme: ColorScheme, val papo: PapoColors, val dark: Boolean) {
    /** A cor que [token] tem neste tema (a da predefinição ou a trocada). */
    fun colorOf(token: ThemeToken): Color = when (token) {
        ThemeToken.Accent -> papo.accent
        ThemeToken.Background -> colorScheme.background
        ThemeToken.Surface -> colorScheme.surfaceContainer
        ThemeToken.Rail -> papo.rail
        ThemeToken.Text -> colorScheme.onSurface
        ThemeToken.TextMuted -> colorScheme.onSurfaceVariant
        ThemeToken.Mention -> papo.mention
        ThemeToken.MentionBackground -> papo.highlightBar
    }
}

/**
 * As cores de [spec]: a predefinição no modo certo e, por cima, as cores trocadas à mão para esse modo. As cores de texto
 * sobre um fundo trocado saem por contraste, então um fundo claro num tema escuro não deixa o texto ilegível.
 */
fun resolveTheme(spec: ThemeSpec, systemDark: Boolean): ResolvedTheme {
    val variant = spec.variant(systemDark)
    val base = ThemePresets.find(spec.preset).theme(variant)
    return base.withOverrides(spec.overrides[variant].orEmpty())
}

private const val MinTextContrast = 4.5f
private const val MinMutedContrast = 3f

/** O que a plataforma precisa do tema fora do Compose: o fundo da janela (ARGB) e se o tema é escuro. */
class WindowStyle(val backgroundArgb: Int, val dark: Boolean)

fun windowStyleOf(spec: ThemeSpec, systemDark: Boolean): WindowStyle =
    resolveTheme(spec, systemDark).let { WindowStyle(it.colorScheme.background.toArgb(), it.dark) }

/** As cores trocadas à mão, sempre opacas: a transparência não faz parte da escolha. */
private typealias Picks = Map<ThemeToken, Color>

private fun ResolvedTheme.withOverrides(overrides: Map<ThemeToken, Int>): ResolvedTheme {
    if (overrides.isEmpty()) return this
    val picks: Picks = overrides.mapValues { Color(it.value or OpaqueMask) }
    return withGrounds(picks).withTextColors(picks).withAccent(picks).withRail(picks).withMentions(picks)
}

/** Fundo e áreas de itens. As áreas saem da cor escolhida, mais claras ou mais escuras, como numa predefinição. */
private fun ResolvedTheme.withGrounds(picks: Picks): ResolvedTheme {
    var scheme = colorScheme
    picks[ThemeToken.Background]?.let { scheme = scheme.copy(background = it, surface = it) }
    picks[ThemeToken.Surface]?.let { surface ->
        val edge = if (dark) Color.Black else Color.White
        scheme = scheme.copy(
            surfaceContainerLowest = blend(surface, edge, 0.5f),
            surfaceContainerLow = blend(surface, edge, 0.25f),
            surfaceContainer = surface,
            surfaceContainerHigh = blend(surface, scheme.onSurface, 0.06f),
            surfaceContainerHighest = blend(surface, scheme.onSurface, 0.12f),
            surfaceVariant = surface,
        )
    }
    return ResolvedTheme(scheme, papo, dark)
}

/**
 * Texto e contornos. O texto escolhido vale como está; o que não foi escolhido fica como a predefinição o definiu, a não
 * ser que o fundo trocado o torne ilegível, e então vira preto ou branco. Sem nenhuma troca de fundo, de áreas ou de
 * texto nada muda.
 */
private fun ResolvedTheme.withTextColors(picks: Picks): ResolvedTheme {
    val touched = listOf(ThemeToken.Background, ThemeToken.Surface, ThemeToken.Text, ThemeToken.TextMuted).any { it in picks }
    if (!touched) return this
    val grounds = with(colorScheme) { listOf(background, surfaceContainer, surfaceContainerHighest, surfaceContainerLowest) }
    val text = picks[ThemeToken.Text] ?: readable(colorScheme.onSurface, grounds, MinTextContrast)
    val muted = picks[ThemeToken.TextMuted] ?: readable(colorScheme.onSurfaceVariant, grounds, MinTextContrast)
    val scheme = colorScheme.copy(
        onBackground = text,
        onSurface = text,
        onSurfaceVariant = muted,
        outline = blend(colorScheme.surfaceContainer, text, 0.5f),
        outlineVariant = blend(colorScheme.surfaceContainer, text, 0.2f),
    )
    return ResolvedTheme(scheme, papo, dark)
}

private fun ResolvedTheme.withAccent(picks: Picks): ResolvedTheme {
    val accent = picks[ThemeToken.Accent] ?: return this
    val container = blend(colorScheme.background, accent, 0.3f)
    val scheme = colorScheme.copy(
        primary = accent,
        onPrimary = bestOn(accent),
        primaryContainer = container,
        onPrimaryContainer = readable(colorScheme.onPrimaryContainer, listOf(container), MinTextContrast),
    )
    return ResolvedTheme(scheme, papo.copy(accent = accent, onAccent = bestOn(accent)), dark)
}

private fun ResolvedTheme.withRail(picks: Picks): ResolvedTheme {
    val rail = picks[ThemeToken.Rail] ?: return this
    val colors = papo.copy(
        rail = rail,
        onRail = readable(papo.onRail, listOf(rail), MinTextContrast),
        onRailMuted = readable(papo.onRailMuted, listOf(rail), MinMutedContrast),
    )
    return ResolvedTheme(colorScheme, colors, dark)
}

private fun ResolvedTheme.withMentions(picks: Picks): ResolvedTheme {
    var colors = papo
    picks[ThemeToken.Mention]?.let { colors = colors.copy(mention = it, onMention = bestOn(it)) }
    // Mantém a transparência da predefinição: a cor sobre o texto da mensagem não pode escondê-lo.
    picks[ThemeToken.MentionBackground]?.let { colors = colors.copy(highlight = it.copy(alpha = colors.highlight.alpha), highlightBar = it) }
    return ResolvedTheme(colorScheme, colors, dark)
}

private const val OpaqueMask = 0xFF000000.toInt()

private fun blend(from: Color, to: Color, fraction: Float): Color = Color(
    red = from.red + (to.red - from.red) * fraction,
    green = from.green + (to.green - from.green) * fraction,
    blue = from.blue + (to.blue - from.blue) * fraction,
)

/** Luminância relativa (WCAG 2) de uma cor opaca. */
fun luminanceOf(color: Color): Float {
    fun channel(value: Float) = if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}

/** A razão de contraste (WCAG 2) entre duas cores opacas, de 1 (iguais) a 21 (preto e branco). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = luminanceOf(a)
    val lb = luminanceOf(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** Preto ou branco, o que mais contrasta com [background]: a cor do que se escreve sobre ele. */
fun bestOn(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White else Color.Black

/** [preferred] se ele atinge [minimum] de contraste sobre todos os [backgrounds]; senão preto ou branco, o que for mais legível. */
private fun readable(preferred: Color, backgrounds: List<Color>, minimum: Float): Color {
    if (backgrounds.all { contrastRatio(preferred, it) >= minimum }) return preferred
    fun worst(color: Color) = backgrounds.minOf { contrastRatio(color, it) }
    return if (worst(Color.White) >= worst(Color.Black)) Color.White else Color.Black
}
