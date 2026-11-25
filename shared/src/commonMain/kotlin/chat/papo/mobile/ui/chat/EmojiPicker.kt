package chat.papo.mobile.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.EmojiPickerModel
import chat.papo.mobile.chat.EmojiRef
import chat.papo.mobile.chat.PickerCell
import chat.papo.mobile.chat.PickerSection
import chat.papo.mobile.chat.UnicodeEmojiCatalog
import chat.papo.mobile.chat.UnicodeEmojis
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.action_retry
import chat.papo.mobile.resources.emoji_group_activities
import chat.papo.mobile.resources.emoji_group_animals
import chat.papo.mobile.resources.emoji_group_flags
import chat.papo.mobile.resources.emoji_group_food
import chat.papo.mobile.resources.emoji_group_objects
import chat.papo.mobile.resources.emoji_group_people
import chat.papo.mobile.resources.emoji_group_smileys
import chat.papo.mobile.resources.emoji_group_symbols
import chat.papo.mobile.resources.emoji_group_travel
import chat.papo.mobile.resources.emoji_none
import chat.papo.mobile.resources.emoji_search
import chat.papo.mobile.resources.emoji_search_clear
import chat.papo.mobile.resources.emoji_section_recent
import chat.papo.mobile.resources.emoji_section_results
import chat.papo.mobile.resources.emoji_section_results_server
import chat.papo.mobile.resources.emoji_section_server
import chat.papo.mobile.resources.emoji_server_failed
import chat.papo.mobile.ui.components.LocalPlatformUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * O catálogo Unicode só com o que a fonte do aparelho desenha, carregado uma vez por
 * processo. Null enquanto lê o arquivo.
 */
@Composable
fun rememberDrawableEmojis(): UnicodeEmojiCatalog? {
    val platform = LocalPlatformUi.current
    return produceState<UnicodeEmojiCatalog?>(null) {
        value = try {
            UnicodeEmojis.drawable(platform::supportsEmoji)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UnicodeEmojiCatalog(emptyList()) // sem o arquivo só ficam os emojis do servidor
        }
    }.value
}

private fun sectionTitle(section: PickerSection): StringResource = when (section) {
    PickerSection.Recent -> Res.string.emoji_section_recent
    PickerSection.Server -> Res.string.emoji_section_server
    PickerSection.ServerResults -> Res.string.emoji_section_results_server
    PickerSection.UnicodeResults -> Res.string.emoji_section_results
    PickerSection.Smileys -> Res.string.emoji_group_smileys
    PickerSection.People -> Res.string.emoji_group_people
    PickerSection.Animals -> Res.string.emoji_group_animals
    PickerSection.Food -> Res.string.emoji_group_food
    PickerSection.Travel -> Res.string.emoji_group_travel
    PickerSection.Activities -> Res.string.emoji_group_activities
    PickerSection.Objects -> Res.string.emoji_group_objects
    PickerSection.Symbols -> Res.string.emoji_group_symbols
    PickerSection.Flags -> Res.string.emoji_group_flags
}

/** Um emoji representando o grupo na faixa de atalhos; recentes e servidor usam ícone. */
private fun sectionGlyph(section: PickerSection): String? = when (section) {
    PickerSection.Smileys -> "😀"
    PickerSection.People -> "👋"
    PickerSection.Animals -> "🐻"
    PickerSection.Food -> "🍔"
    PickerSection.Travel -> "🚗"
    PickerSection.Activities -> "⚽"
    PickerSection.Objects -> "💡"
    PickerSection.Symbols -> "🔣"
    PickerSection.Flags -> "🏁"
    else -> null
}

/**
 * Pulso dos lugares reservados enquanto algo carrega. O valor é lido só no desenho
 * ([drawBehind]), então a animação não recompõe a grade a cada quadro.
 */
@Composable
private fun rememberLoadingPulse(): State<Float> =
    rememberInfiniteTransition(label = "emoji-loading").animateFloat(
        initialValue = 0.10f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700, easing = LinearEasing), RepeatMode.Reverse),
        label = "emoji-loading-pulse",
    )

@Composable
private fun PlaceholderCell(pulse: State<Float>) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(CELL)
            .padding(9.dp)
            .drawBehind { drawCircle(color.copy(alpha = pulse.value)) },
    )
}

/**
 * Conteúdo do seletor de emoji: busca por nome (sem acento), faixa de grupos e a grade
 * virtualizada com recentes, emojis do servidor e os grupos Unicode. Serve à gaveta do
 * compositor e à folha de reações.
 *
 * [headerModifier] vai na área de cima (alça, busca e abas): a gaveta usa para arrastar.
 * [gridModifier] vai em volta da grade (a gaveta põe ali a rolagem aninhada).
 * A escolha entra nos recentes da conta antes de chegar em [onPick].
 */
@Composable
fun EmojiPickerContent(
    session: AccountSession,
    onPick: (EmojiRef) -> Unit,
    modifier: Modifier = Modifier,
    headerModifier: Modifier = Modifier,
    gridModifier: Modifier = Modifier,
    showHandle: Boolean = false,
    onSearchFocusChange: (Boolean) -> Unit = {},
) {
    val unicode = rememberDrawableEmojis()
    val server by session.emojis.state.collectAsStateWithLifecycle()
    val recents by session.recentEmojis.items.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val pulse = rememberLoadingPulse()

    // Montar ~2400 células e buscar em 1900 emojis não é trabalho para a thread da tela.
    // Enquanto a primeira montagem não chega, a grade mostra só lugares reservados.
    val cells by produceState<List<PickerCell>?>(null, query, unicode, server, recents) {
        val catalog = unicode ?: return@produceState
        val built = withContext(Dispatchers.Default) { EmojiPickerModel.cells(query, catalog, server, recents) }
        if (built != value) value = built
    }

    // Abrir o seletor relê a lista do servidor se ela estiver velha (a carga é espaçada).
    LaunchedEffect(session) { session.emojis.start() }
    LaunchedEffect(query) { gridState.scrollToItem(0) }

    Column(modifier) {
        Column(headerModifier.fillMaxWidth()) {
            if (showHandle) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(Res.string.emoji_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.emoji_search_clear))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .onFocusChanged { onSearchFocusChange(it.isFocused) },
            )

            val shown = cells
            if (query.isBlank() && shown != null) {
                val headers = remember(shown) {
                    shown.withIndex().mapNotNull { (index, cell) -> (cell as? PickerCell.Header)?.let { index to it.section } }
                }
                val current by remember(headers) {
                    derivedStateOf { headers.lastOrNull { it.first <= gridState.firstVisibleItemIndex }?.second }
                }
                // A faixa acompanha a rolagem: a aba da seção na tela fica visível, com duas antes dela.
                val tabsScroll = rememberScrollState()
                val density = LocalDensity.current
                LaunchedEffect(current, headers) {
                    val position = headers.indexOfFirst { it.second == current }
                    if (position >= 0) {
                        val tab = with(density) { TAB_WIDTH.toPx() }
                        tabsScroll.animateScrollTo(((position - 2) * tab).toInt().coerceAtLeast(0))
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(tabsScroll).padding(horizontal = 8.dp)) {
                    for ((index, section) in headers) {
                        val title = stringResource(sectionTitle(section))
                        Box(
                            Modifier
                                .size(width = TAB_WIDTH, height = 40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (section == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .clickable(onClickLabel = title) { scope.launch { gridState.animateScrollToItem(index) } }
                                .semantics { contentDescription = title },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (section) {
                                PickerSection.Recent -> Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(22.dp))
                                PickerSection.Server -> Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.size(22.dp))
                                else -> Text(sectionGlyph(section).orEmpty(), fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        }

        if (server.failed) ServerFailed(session)

        Box(gridModifier.weight(1f).fillMaxWidth()) {
            val shown = cells
            when {
                shown == null -> LoadingGrid(pulse)
                shown.isEmpty() -> Text(
                    stringResource(Res.string.emoji_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> EmojiGrid(session, shown, gridState, pulse) { ref ->
                    session.recentEmojis.used(ref)
                    onPick(ref)
                }
            }
        }
    }
}

@Composable
private fun EmojiGrid(
    session: AccountSession,
    cells: List<PickerCell>,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    pulse: State<Float>,
    pick: (EmojiRef) -> Unit,
) {
    // Um medidor para a grade toda, com cache dos layouts dos emojis já vistos.
    val measurer = rememberTextMeasurer(cacheSize = GLYPH_CACHE)
    val glyphStyle = GLYPH_STYLE
    // Grade preguiçosa: só as células na tela (e um pouco em volta) existem de fato.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CELL),
        state = state,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            count = cells.size,
            key = { cells[it].key },
            span = { index -> if (cells[index] is PickerCell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { index ->
                when (cells[index]) {
                    is PickerCell.Header -> 0
                    is PickerCell.Unicode -> 1
                    is PickerCell.Server -> 2
                    is PickerCell.Placeholder -> 3
                }
            },
        ) { index ->
            when (val cell = cells[index]) {
                is PickerCell.Header -> Text(
                    stringResource(sectionTitle(cell.section)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp),
                )
                is PickerCell.Unicode -> EmojiCell(cell.emoji.name, onClick = { pick(EmojiRef.Unicode(cell.emoji.char)) }) {
                    EmojiGlyph(cell.emoji.char, measurer, glyphStyle)
                }
                is PickerCell.Server -> EmojiCell(":${cell.emoji.name}:", onClick = { pick(EmojiRef.Custom(cell.emoji.id)) }) {
                    EmojiImage(session, cell.emoji.id, Modifier.size(32.dp), placeholder = pulse)
                }
                is PickerCell.Placeholder -> PlaceholderCell(pulse)
            }
        }
    }
}

/** Grade só de lugares reservados, enquanto o catálogo ainda está sendo preparado. */
@Composable
private fun LoadingGrid(pulse: State<Float>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CELL),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
                Modifier
                    .padding(start = 8.dp, top = 18.dp, bottom = 8.dp)
                    .size(width = 120.dp, height = 12.dp)
                    .drawBehind { drawRect(Color.Gray.copy(alpha = pulse.value)) },
            )
        }
        items(48, contentType = { 3 }) { PlaceholderCell(pulse) }
    }
}

/** Aviso de que a leitura dos emojis do servidor falhou, com a opção de tentar de novo. */
@Composable
private fun ServerFailed(session: AccountSession) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.emoji_server_failed),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = { session.emojis.start(force = true) }) { Text(stringResource(Res.string.action_retry)) }
    }
}

@Composable
private fun EmojiCell(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    // Sem `clip`: ele daria a cada célula uma camada de desenho própria, e a grade tem centenas.
    // O toque mostra um ripple redondo do tamanho da célula, que não precisa de recorte.
    Box(
        Modifier
            .size(CELL)
            .clickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = CELL / 2),
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Um emoji Unicode desenhado direto, a partir do [measurer] da grade. O layout do glifo
 * (com a busca da fonte de emoji) fica no cache dele, então uma linha que volta à tela não
 * é medida de novo, e a célula não carrega um `Text` inteiro.
 */
@Composable
private fun EmojiGlyph(char: String, measurer: TextMeasurer, style: TextStyle) {
    Spacer(
        Modifier
            .size(CELL)
            .drawWithCache {
                val layout = measurer.measure(char, style)
                val topLeft = Offset((size.width - layout.size.width) / 2f, (size.height - layout.size.height) / 2f)
                onDrawBehind { drawText(layout, topLeft = topLeft) }
            },
    )
}

/**
 * Seletor numa folha de baixo, para reagir a uma mensagem. Abre pela metade; arrastar
 * para cima abre inteira, e a busca abre inteira para o teclado não cobrir a grade.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(session: AccountSession, onPick: (EmojiRef) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        EmojiPickerContent(
            session = session,
            onPick = onPick,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding(),
            onSearchFocusChange = { focused -> if (focused) scope.launch { sheetState.expand() } },
        )
    }
}

private val CELL = 46.dp

/** Layouts de emoji guardados pelo medidor da grade: algumas telas cheias para ir e voltar. */
private const val GLYPH_CACHE = 400

private val GLYPH_STYLE = TextStyle(fontSize = 28.sp)
private val TAB_WIDTH = 44.dp
