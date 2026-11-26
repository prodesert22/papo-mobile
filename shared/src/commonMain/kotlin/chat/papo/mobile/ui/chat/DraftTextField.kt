package chat.papo.mobile.ui.chat

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.takeOrElse
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.DraftField
import chat.papo.mobile.chat.EmojiDraft
import chat.papo.mobile.chat.EmojiMatch
import kotlin.math.roundToInt

// Cada emoji aparece no campo como um espaço do tamanho de um `em` (o caractere abaixo) com o espaçamento entre letras que
// completa 1,25 em, a largura do emoji no texto da mensagem; a imagem é desenhada em cima dele.
private const val EMOJI_SLOT = ' '
private const val EMOJI_WIDTH_EM = 1.25f
private val SlotStyle = SpanStyle(letterSpacing = (EMOJI_WIDTH_EM - 1f).em)

/** Linhas que o campo mostra antes de rolar. */
private const val MAX_LINES = 6

/**
 * Os emojis do servidor de uma conta no texto do compositor. O texto continua `:nome:` (o que se guarda, se envia e se copia):
 * esta [VisualTransformation] só troca, na tela, cada `:nome:` por um espaço da largura do emoji, e [settle] faz o emoji se
 * comportar como um caractere só. Os `:nome:` que a conta não tem, e os de dentro de código ou de link, ficam como texto,
 * como na mensagem. Use uma instância por [idByName]: ela guarda a última busca, que o campo, a imagem e a edição pedem juntos.
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

    override fun filter(text: AnnotatedString): TransformedText {
        val found = find(text.text)
        if (found.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val shown = buildAnnotatedString {
            var last = 0
            for (emoji in found) {
                append(text.subSequence(last, emoji.start))
                withStyle(SlotStyle) { append(EMOJI_SLOT) }
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
 * O campo de texto do compositor. É o `TextField` do Material, com [emojis] mostrados como imagem animada no lugar do
 * `:nome:`, no tamanho do texto. O campo cresce até [MAX_LINES] linhas e rola dali em diante; a rolagem é daqui e não
 * do campo, porque a imagem precisa rolar junto com o texto e o campo não diz quanto rolou.
 */
@Composable
fun DraftTextField(
    session: AccountSession,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
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
    val textLayout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    val lineHeight = textStyle.lineHeight.takeOrElse { textStyle.fontSize * 1.5f }
    val maxHeight = with(LocalDensity.current) { lineHeight.toDp() } * MAX_LINES
    val found = emojis.find(value.text)
    // Com a rolagem aqui, o campo não leva mais o cursor para a vista quando só ele muda (setas, o gesto do teclado).
    LaunchedEffect(value.selection.end, textLayout.value) {
        scroll.showCursor(textLayout.value, EmojiDraft.toShown(found, value.selection.end), viewportHeight)
    }

    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .defaultMinSize(minWidth = TextFieldDefaults.MinWidth, minHeight = TextFieldDefaults.MinHeight)
                .semantics { editableText = AnnotatedString(value.text) },
            enabled = enabled,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            visualTransformation = emojis,
            onTextLayout = { textLayout.value = it },
            interactionSource = interactionSource,
            cursorBrush = SolidColor(colors.cursorColor),
            decorationBox = { innerTextField ->
                TextFieldDefaults.DecorationBox(
                    value = value.text,
                    innerTextField = {
                        Box(Modifier.fillMaxWidth().heightIn(max = maxHeight).onSizeChanged { viewportHeight = it.height }.verticalScroll(scroll)) {
                            innerTextField()
                            EmojiImages(session, found, textLayout)
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
            },
        )
    }
}

/** Uma imagem por emoji de [found], sobre o espaço dele no texto de [textLayout]. */
@Composable
private fun EmojiImages(session: AccountSession, found: List<EmojiMatch>, textLayout: State<TextLayoutResult?>) {
    // A mesma imagem pode aparecer mais de uma vez: a chave tem o id e a ordem dele, para inserir outro emoji antes
    // não trocar a imagem de quem já estava (e decodificá-la de novo).
    val seen = HashMap<String, Int>()
    for (emoji in found) {
        val occurrence = seen.getOrElse(emoji.id) { 0 }
        seen[emoji.id] = occurrence + 1
        key(emoji.id, occurrence) {
            EmojiImage(session, emoji.id, Modifier.inSlot(textLayout, EmojiDraft.toShown(found, emoji.start)))
        }
    }
}

/**
 * Põe a imagem sobre o caractere [shown] do texto mostrado, quadrada e no meio da linha. Sem posição (o texto ainda
 * não foi medido, ou o que foi medido é de antes), a imagem não aparece. A posição é lida na medição, então a
 * imagem acompanha o texto no mesmo quadro, sem recompor.
 */
private fun Modifier.inSlot(textLayout: State<TextLayoutResult?>, shown: Int): Modifier = layout { measurable, _ ->
    val slot = slotAt(textLayout.value, shown)
    if (slot == null) {
        layout(0, 0) {}
    } else {
        val side = minOf(slot.width, slot.height).roundToInt().coerceAtLeast(0)
        val placeable = measurable.measure(Constraints.fixed(side, side))
        layout(0, 0) {
            placeable.place(
                (slot.left + (slot.width - side) / 2).roundToInt(),
                (slot.top + (slot.height - side) / 2).roundToInt(),
            )
        }
    }
}

/** Rola o mínimo para o cursor (a posição [cursor] do texto mostrado) ficar na janela de [viewport] pixels. */
private suspend fun ScrollState.showCursor(layout: TextLayoutResult?, cursor: Int, viewport: Int) {
    if (layout == null || viewport <= 0 || cursor > layout.layoutInput.text.length) return
    val line = layout.getCursorRect(cursor)
    when {
        line.top < value -> scrollTo(line.top.roundToInt())
        line.bottom > value + viewport -> scrollTo(line.bottom.roundToInt() - viewport)
    }
}

private fun slotAt(result: TextLayoutResult?, shown: Int): Rect? {
    val text = result?.layoutInput?.text?.text ?: return null
    if (shown >= text.length || text[shown] != EMOJI_SLOT) return null
    return result.getBoundingBox(shown)
}
