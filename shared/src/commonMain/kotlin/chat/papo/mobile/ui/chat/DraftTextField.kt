package chat.papo.mobile.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.DraftField
import chat.papo.mobile.chat.EmojiDraft
import chat.papo.mobile.chat.EmojiMatch
import kotlin.math.roundToInt

// Cada emoji aparece no campo como um espaço da largura de um `em` (U+2003), que a imagem cobre, seguido de um junta-palavras sem
// largura (U+2060). O junta-palavras é o que impede o espaço de ficar no fim da linha: o Android deixa um espaço desses pendurado
// para fora do campo, e com ele o emoji sairia do texto. Esta largura vem do próprio espaço, e não de espaçamento entre letras, que
// o Android reparte de um jeito que depende da posição na linha.
private const val EMOJI_SLOT = '\u2003'
private const val EMOJI_JOINER = '\u2060'
private const val EMOJI_SHOWN = "$EMOJI_SLOT$EMOJI_JOINER"

/** Linhas que o campo mostra antes de rolar. */
private const val MAX_LINES = 6

/**
 * Os emojis do servidor de uma conta no texto do compositor. O texto continua `:nome:` (o que se guarda, se envia e se copia):
 * [output] só troca, na tela, cada `:nome:` por um espaço da largura de um em, e [input] faz o emoji se comportar como um
 * caractere só ([settle]). Os `:nome:` que a conta não tem, e os de dentro de código ou de link, ficam como texto, como na
 * mensagem. Use uma instância por [idByName]: ela guarda a última busca, que o campo, a imagem e a edição pedem juntos.
 * Como [VisualTransformation] ([filter]) ela diz ao `TextFieldDefaults.DecorationBox` o que o campo mostra, para o
 * texto de apoio sumir só quando não há nada a ver.
 */
@Stable
class DraftEmojis(private val idByName: Map<String, String>) : VisualTransformation {
    private var lastText: String? = null
    private var lastFound: List<EmojiMatch> = emptyList()

    /** Os emojis de [text]. */
    fun find(text: String): List<EmojiMatch> {
        if (text != lastText) {
            lastFound = EmojiDraft.find(text, idByName)
            lastText = text
        }
        return lastFound
    }

    /**
     * O que o campo mostra no lugar do texto: a mesma troca de [filter], feita no campo de `TextFieldState`, que tira das
     * trocas o mapeamento das posições (o cursor e a seleção nunca ficam dentro de um emoji). Pelo fim, para as posições
     * seguintes continuarem valendo enquanto se troca.
     */
    val output = OutputTransformation {
        for (emoji in find(asCharSequence().toString()).asReversed()) replace(emoji.start, emoji.end, EMOJI_SHOWN)
    }

    /**
     * A edição do usuário no campo (o teclado, uma seleção, colar), acertada para o emoji ser um caractere só ([settle]):
     * apagar uma parte do `:nome:` apaga todo ele, e o cursor não fica no meio. Só entra uma troca de texto quando o
     * acerto mudou o texto, porque trocar o texto encerra a composição do teclado.
     */
    val input = InputTransformation {
        val typed = TextFieldValue(asCharSequence().toString(), selection)
        val settled = settle(TextFieldValue(originalText.toString(), originalSelection), typed)
        if (settled.text != typed.text) replace(0, length, settled.text)
        selection = settled.selection
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val found = find(text.text)
        if (found.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val shown = buildAnnotatedString {
            var last = 0
            for (emoji in found) {
                append(text.subSequence(last, emoji.start))
                append(EMOJI_SLOT)
                append(EMOJI_JOINER)
                last = emoji.end
            }
            append(text.subSequence(last, text.length))
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = EmojiDraft.toShown(found, offset)
            override fun transformedToOriginal(offset: Int) = EmojiDraft.toDraft(found, offset)
        }
        return TransformedText(shown, mapping)
    }

    /**
     * O [value] que o campo entrega depois de uma edição ou de um toque, acertado para o emoji ser um caractere só
     * ([EmojiDraft.settle]). Se a edição mudou o texto, a composição do teclado acaba, porque o texto dela mudou.
     */
    fun settle(previous: TextFieldValue, value: TextFieldValue): TextFieldValue {
        val settled = EmojiDraft.settle(previous.toField(), find(previous.text), value.toField(), idByName)
        val selection = TextRange(settled.selectionStart, settled.selectionEnd)
        return when {
            settled.text != value.text -> TextFieldValue(settled.text, selection)
            selection != value.selection -> value.copy(selection = selection)
            else -> value
        }
    }

    private fun TextFieldValue.toField() = DraftField(text, selection.start, selection.end)
}

/**
 * O campo de texto do compositor. É o `TextField` do Material sobre um [TextFieldState], com [emojis] mostrados como
 * imagem animada no lugar do `:nome:`, no tamanho do texto. O campo cresce até [MAX_LINES] linhas e rola dali em diante,
 * e as imagens rolam junto: a posição de cada uma vem do texto medido e desconta o quanto o campo rolou.
 */
@Composable
fun DraftTextField(
    session: AccountSession,
    state: TextFieldState,
    emojis: DraftEmojis,
    placeholder: @Composable () -> Unit,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    shape: Shape,
    colors: TextFieldColors,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val textColor = when {
        !enabled -> colors.disabledTextColor
        focused -> colors.focusedTextColor
        else -> colors.unfocusedTextColor
    }
    val textStyle = LocalTextStyle.current.merge(TextStyle(color = textColor))
    // O texto medido, como o campo o entrega: quem o lê (a posição das imagens) refaz a medição quando ele muda.
    val textLayout = remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    val scroll = rememberScrollState()
    val found = emojis.find(state.text.toString())
    val decorator = TextFieldDecorator { innerTextField ->
        TextFieldDefaults.DecorationBox(
            value = state.text.toString(),
            innerTextField = {
                Box(Modifier.fillMaxWidth().clipToBounds()) {
                    innerTextField()
                    EmojiImages(session, found, textLayout, scroll)
                }
            },
            enabled = enabled,
            singleLine = false,
            visualTransformation = emojis,
            interactionSource = interactionSource,
            placeholder = placeholder,
            shape = shape,
            colors = colors,
        )
    }

    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            state = state,
            modifier = modifier.defaultMinSize(minWidth = TextFieldDefaults.MinWidth, minHeight = TextFieldDefaults.MinHeight),
            enabled = enabled,
            inputTransformation = emojis.input,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = MAX_LINES),
            onTextLayout = { getResult -> if (textLayout.value !== getResult) textLayout.value = getResult },
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.cursorColor),
            outputTransformation = emojis.output,
            decorator = decorator,
            scrollState = scroll,
        )
    }
}

/** Uma imagem por emoji de [found], sobre o espaço dele no texto de [textLayout]. */
@Composable
private fun EmojiImages(session: AccountSession, found: List<EmojiMatch>, textLayout: State<(() -> TextLayoutResult?)?>, scroll: ScrollState) {
    // A mesma imagem pode aparecer mais de uma vez: a chave tem o id e a ordem dele, para inserir outro emoji antes
    // não trocar a imagem de quem já estava (e decodificá-la de novo).
    val seen = HashMap<String, Int>()
    found.forEachIndexed { index, emoji ->
        val occurrence = seen.getOrElse(emoji.id) { 0 }
        seen[emoji.id] = occurrence + 1
        key(emoji.id, occurrence) {
            EmojiImage(session, emoji.id, Modifier.inSlot(textLayout, scroll, EmojiDraft.slotOf(found, index)))
        }
    }
}

/**
 * Põe a imagem sobre o caractere [shown] do texto mostrado, quadrada e no meio da linha, descontando o quanto o campo
 * rolou ([scroll]). Sem posição (o texto ainda não foi medido, ou o que foi medido é de antes), a imagem não aparece. A
 * posição é lida na medição e a rolagem na colocação, então a imagem acompanha o texto no mesmo quadro, sem recompor.
 */
private fun Modifier.inSlot(textLayout: State<(() -> TextLayoutResult?)?>, scroll: ScrollState, shown: Int): Modifier = layout { measurable, _ ->
    val slot = slotAt(textLayout.value?.invoke(), shown)
    if (slot == null) {
        layout(0, 0) {}
    } else {
        val side = minOf(slot.width, slot.height).roundToInt().coerceAtLeast(0)
        val placeable = measurable.measure(Constraints.fixed(side, side))
        layout(0, 0) {
            placeable.place(
                (slot.left + (slot.width - side) / 2).roundToInt(),
                (slot.top + (slot.height - side) / 2).roundToInt() - scroll.value,
            )
        }
    }
}

private fun slotAt(result: TextLayoutResult?, shown: Int): Rect? {
    val text = result?.layoutInput?.text?.text ?: return null
    if (shown >= text.length || text[shown] != EMOJI_SLOT) return null
    return result.getBoundingBox(shown)
}
