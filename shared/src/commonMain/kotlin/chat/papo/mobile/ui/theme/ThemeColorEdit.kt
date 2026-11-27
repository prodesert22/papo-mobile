package chat.papo.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

private const val Opaque = 0xFF000000.toInt()

/**
 * Lê `#RRGGBB`, `RRGGBB` ou a forma curta `#RGB`, em maiúsculas ou minúsculas, e devolve o ARGB opaco. Nulo para qualquer
 * outra coisa, inclusive a forma com transparência (`#RRGGBBAA`): a transparência não faz parte da escolha de uma cor do tema.
 */
fun parseHexColor(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6 && digits.length != 3) return null
    // `toInt(16)` aceitaria sinais ("-1a"), então os dígitos são conferidos um a um.
    if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val full = if (digits.length == 3) digits.flatMap { listOf(it, it) }.joinToString("") else digits
    return Opaque or full.toInt(16)
}

/** `#RRGGBB` em maiúsculas; a transparência de [argb] é ignorada. */
fun formatHexColor(argb: Int): String {
    val rgb = argb and 0xFFFFFF
    return "#" + rgb.toString(16).uppercase().padStart(6, '0')
}

/** O [token] tem uma cor trocada à mão no [variant] (e portanto há o que restaurar)? */
fun ThemeSpec.hasOverride(variant: ThemeVariant, token: ThemeToken): Boolean =
    overrides[variant]?.containsKey(token) == true

/** Grava a cor de [token] no [variant] como opaca. É o que o editor chama a cada escolha. */
fun ThemeSpec.withPickedColor(variant: ThemeVariant, token: ThemeToken, argb: Int): ThemeSpec =
    withOverride(variant, token, argb or Opaque)

/** Volta [token] à cor da predefinição no [variant]. */
fun ThemeSpec.withRestoredColor(variant: ThemeVariant, token: ThemeToken): ThemeSpec =
    withOverride(variant, token, null)

/** A cor efetiva de [token] em [spec] (a da predefinição ou a trocada), como ARGB opaco. */
fun effectiveColor(spec: ThemeSpec, systemDark: Boolean, token: ThemeToken): Int =
    resolveTheme(spec, systemDark).colorOf(token).toArgb() or Opaque

/**
 * Os tokens de texto ([ThemeToken.Text], [ThemeToken.TextMuted]) cujo contraste com o fundo ou com as áreas de itens
 * está abaixo do mínimo de leitura (o do secundário é menor porque é texto de apoio). Só informa: nunca impede uma escolha.
 */
fun lowContrastTexts(theme: ResolvedTheme): Set<ThemeToken> {
    val grounds = listOf(theme.colorScheme.background, theme.colorScheme.surfaceContainer)
    fun low(text: Color, minimum: Float) = grounds.any { contrastRatio(text, it) < minimum }
    return buildSet {
        if (low(theme.colorScheme.onSurface, MinTextContrast)) add(ThemeToken.Text)
        if (low(theme.colorScheme.onSurfaceVariant, MinMutedContrast)) add(ThemeToken.TextMuted)
    }
}

/** Editar [token] pode deixar o texto ilegível? Só o texto e os fundos que ele pisa entram no aviso. */
fun affectsTextContrast(token: ThemeToken): Boolean =
    token == ThemeToken.Text || token == ThemeToken.TextMuted || token == ThemeToken.Background || token == ThemeToken.Surface

/** Amostras para escolher uma cor sem abrir o seletor: neutros e as cores de destaque mais comuns. */
val QuickSwatches: List<Int> = listOf(
    0xFF000000, 0xFF1E1E1E, 0xFF5F6368, 0xFFBDBDBD, 0xFFFFFFFF,
    0xFFE5484D, 0xFFFF8A3D, 0xFFFFB238, 0xFFF2D13E, 0xFF3DBE6D,
    0xFF2DB7A8, 0xFF3D9BE9, 0xFF3D5AE9, 0xFF8B5CF6, 0xFFE0489C,
).map { it.toInt() }
