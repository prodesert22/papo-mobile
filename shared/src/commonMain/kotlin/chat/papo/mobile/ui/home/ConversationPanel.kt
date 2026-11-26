package chat.papo.mobile.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.conversation_panel_resume
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Os dois lugares do painel da conversa: [Open] com a conversa ocupando a tela, [Closed] com a lista de canais à vista. */
enum class PanelValue { Open, Closed }

/** Um pedido para o painel assentar em [target]; cada pedido é um objeto novo, e o mesmo destino pedido de novo vale outra vez. */
internal class PanelRequest(val target: PanelValue)

/**
 * O painel da conversa por cima da lista de canais: a última conversa de cada conta ([chats]) e onde o painel está.
 * Vive acima da navegação, então abrir o perfil ou a busca por cima da tela principal e voltar o encontra como estava;
 * girar o aparelho guarda os dois ([Saver]). Quem trava o app o perde, e ao destravar a lista aparece.
 */
@Stable
class ConversationPanelState internal constructor(chats: ParkedChats, open: Boolean) {
    constructor() : this(ParkedChats.Empty, open = false)

    var chats: ParkedChats by mutableStateOf(chats)
        private set

    internal val anchored = AnchoredDraggableState(if (open) PanelValue.Open else PanelValue.Closed)

    internal var request: PanelRequest? by mutableStateOf(null)
        private set

    /** [chat] passa a ser a última conversa de [accountId] e o painel abre sobre ela. */
    fun open(accountId: String, chat: ParkedChat) {
        chats = chats.opened(accountId, chat)
        request = PanelRequest(PanelValue.Open)
    }

    /** Fecha o painel, deixando a lista à vista. */
    fun close() {
        request = PanelRequest(PanelValue.Closed)
    }

    /** As contas que saíram levam as conversas delas. */
    fun retain(accountIds: Set<String>) {
        chats = chats.retaining(accountIds)
    }

    internal fun consume(done: PanelRequest) {
        if (request === done) request = null
    }

    companion object {
        val Saver: Saver<ConversationPanelState, Any> = listSaver(
            save = { state -> listOf<Any>(state.anchored.targetValue == PanelValue.Open) + state.chats.encode() },
            restore = { saved ->
                ConversationPanelState(ParkedChats.decode(saved.drop(1).filterIsInstance<String>()), open = saved.firstOrNull() == true)
            },
        )
    }
}

@Composable
fun rememberConversationPanelState(): ConversationPanelState =
    rememberSaveable(saver = ConversationPanelState.Saver) { ConversationPanelState() }

/**
 * A faixa da conversa que fica à vista na borda direita com a lista aberta: tocar nela ou puxá-la traz a conversa de volta.
 * O painel é sempre estacionado à direita da tela, como as telas empilhadas entram e saem: por isso as medidas daqui são
 * absolutas (esquerda e direita), e não do começo e do fim, que se invertem nos idiomas da direita para a esquerda.
 */
private val ParkedStrip = 56.dp

/** Quanto a lista anda para a esquerda enquanto o painel a cobre. */
private val ListParallax = 40.dp

/** Escurecimento da lista com o painel todo aberto. */
private const val ScrimAlpha = 0.32f

/** Quanto o painel precisa ter saído do canto para ter a sombra e os cantos arredondados inteiros. */
private val EdgeReveal = 48.dp
private val PanelCorner = 20.dp
private val PanelShadow = 12.dp

/** O painel que anda sozinho (um toque, o voltar do sistema): o mesmo deslize das telas, 300 ms. */
private val PanelSlide = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

/** A metade do caminho: soltar antes dela volta ao lado de onde o painel saiu, depois dela segue para o outro. */
private val PanelThreshold: (Float) -> Float = { distance -> distance * 0.5f }

/** O que sobra do arrasto solto: uma mola, que herda a velocidade do dedo. */
private val PanelFling = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * A tela principal ([home]: contas e lista de canais) com a conversa ([chat]) num painel por cima dela, como no Discord.
 * [parked] é a última conversa da conta que o [home] mostra; sem ela não há painel, faixa nem gesto.
 *
 * O painel acompanha o dedo nos dois sentidos, a qualquer momento, e solto assenta aberto ou fechado pelo ponto e pela
 * velocidade. Fechado, a conversa fica estacionada à direita e deixa uma faixa à vista. O arraste começa em qualquer lugar
 * (a lista, a faixa, a conversa) e só assume depois da folga de toque no sentido horizontal; quem tem rolagem horizontal
 * própria, ou responde mensagens, fica com o arraste antes e só entrega o que sobra (rolagem aninhada).
 *
 * [chat] recebe a conversa e se ela está à vista (`active`): estacionada, ela não conta as mensagens novas como lidas.
 */
@Composable
fun ConversationPanelHost(
    panel: ConversationPanelState,
    parked: ParkedChat?,
    home: @Composable () -> Unit,
    chat: @Composable (chat: ParkedChat, active: Boolean) -> Unit,
) {
    val state = panel.anchored
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val stripPx = with(density) { ParkedStrip.toPx() }
    var widthPx by remember { mutableIntStateOf(0) }
    val settled = state.settledValue
    val open = settled == PanelValue.Open

    // Passa do meio, ou da velocidade mínima de arremesso do Compose, e o painel segue para o lado que o dedo levava.
    val fling = AnchoredDraggableDefaults.flingBehavior(state = state, positionalThreshold = PanelThreshold, animationSpec = PanelFling)
    val connection = remember(state, fling) { PanelNestedScroll(state, fling) }

    // Os pedidos de quem abre e fecha o painel; esperam as medidas para ter por onde animar.
    LaunchedEffect(panel.request) {
        val request = panel.request ?: return@LaunchedEffect
        try {
            snapshotFlow { widthPx }.first { it > 0 }
            state.animateTo(request.target, PanelSlide)
        } finally {
            panel.consume(request)
        }
    }
    // Voltar à tela principal no meio de uma animação (a tela de cima saiu e levou o painel junto) termina o que ficou pela metade.
    LaunchedEffect(Unit) {
        if (panel.request != null) return@LaunchedEffect
        snapshotFlow { widthPx }.first { it > 0 }
        state.animateTo(state.targetValue, PanelSlide)
    }
    // Sem conversa na conta (outra conta, ou a sessão saiu), o painel volta a ser o de fechado: a próxima abre entrando.
    LaunchedEffect(parked == null) {
        if (parked != null) return@LaunchedEffect
        snapshotFlow { widthPx }.first { it > 0 }
        state.snapTo(PanelValue.Closed)
    }

    // O voltar do sistema, botão ou gesto, com o painel aberto fecha o painel acompanhando o dedo. A conversa pode ter
    // os dela por cima (a gaveta de emoji, a de membros) e esses valem primeiro: são registrados depois deste.
    val back = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = back,
        isBackEnabled = parked != null && open,
        onBackCancelled = { scope.launch { state.animateTo(PanelValue.Open, PanelSlide) } },
        onBackCompleted = { scope.launch { state.animateTo(PanelValue.Closed, PanelSlide) } },
    )
    LaunchedEffect(back) {
        snapshotFlow { back.transitionState }.collect { transition ->
            if (transition !is NavigationEventTransitionState.InProgress || state.offset.isNaN()) return@collect
            val target = state.anchors.positionOf(PanelValue.Closed) * transition.latestEvent.progress
            state.dispatchRawDelta(target - state.offset)
        }
    }

    // Quem sai da conversa (arrastando, ou pelo voltar) deixa o teclado: ele ficaria aberto sobre a lista.
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state) {
        snapshotFlow { state.offset > 1f }.distinctUntilChanged().collect { away ->
            if (away) {
                keyboard?.hide()
                focus.clearFocus()
            }
        }
    }

    // 0 com o painel parado fechado, 1 com ele aberto; lido só nas camadas de desenho, então arrastar não recompõe nada.
    fun openFraction(): Float {
        val closed = state.anchors.maxPosition()
        return if (state.offset.isNaN() || closed.isNaN() || closed <= 0f) 0f else 1f - (state.offset / closed).coerceIn(0f, 1f)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { size ->
                widthPx = size.width
                state.updateAnchors(
                    DraggableAnchors {
                        PanelValue.Open at 0f
                        PanelValue.Closed at (size.width - stripPx).coerceAtLeast(0f)
                    },
                )
            }
            .nestedScroll(connection)
            .anchoredDraggable(state = state, orientation = Orientation.Horizontal, enabled = parked != null, flingBehavior = fling),
    ) {
        // A lista deixa a faixa livre, para o que ela tem na borda direita (os botões do topo) não ficar sob a conversa.
        Box(
            Modifier
                .fillMaxSize()
                .absolutePadding(right = if (parked != null) ParkedStrip else 0.dp)
                .graphicsLayer { translationX = -ListParallax.toPx() * openFraction() }
                // Coberta pelo painel, a lista não é lida nem percorrida pelo leitor de tela.
                .then(if (open && parked != null) Modifier.clearAndSetSemantics {} else Modifier),
        ) { home() }

        if (parked != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = openFraction() * ScrimAlpha }.background(MaterialTheme.colorScheme.scrim))
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val offset = state.offset
                        // Antes de medir não se sabe onde o painel está: nada dele aparece.
                        alpha = if (offset.isNaN()) 0f else 1f
                        translationX = if (offset.isNaN()) 0f else offset
                        // Sombra e cantos crescem com o quanto o painel saiu da borda: aberto, ele é a tela inteira.
                        val reveal = if (offset.isNaN()) 0f else (offset / EdgeReveal.toPx()).coerceIn(0f, 1f)
                        shadowElevation = PanelShadow.toPx() * reveal
                        val corner = CornerSize(PanelCorner.toPx() * reveal)
                        shape = if (reveal > 0f) AbsoluteRoundedCornerShape(topLeft = corner, topRight = CornerSize(0f), bottomRight = CornerSize(0f), bottomLeft = corner) else RectangleShape
                        clip = reveal > 0f
                    }
                    .background(MaterialTheme.colorScheme.background),
            ) {
                // Estacionada, a conversa fica só na vista: o leitor de tela vai para a faixa.
                Box(Modifier.fillMaxSize().then(if (open) Modifier else Modifier.clearAndSetSemantics {})) {
                    chat(parked, open)
                }
                if (!open) {
                    val resume = stringResource(Res.string.conversation_panel_resume)
                    Box(
                        Modifier
                            .align(AbsoluteAlignment.CenterLeft)
                            .fillMaxHeight()
                            .width(ParkedStrip)
                            .clickable(role = Role.Button) { scope.launch { state.animateTo(PanelValue.Open, PanelSlide) } }
                            .semantics { contentDescription = resume },
                    )
                }
            }
        }
    }
}

/**
 * Leva ao painel o arraste que um filho com rolagem própria não usou: as prévias de anexo no começo ou no fim, uma
 * mensagem puxada para o lado contrário ao de responder. A rolagem do filho vem antes; o painel anda com o que sobra.
 *
 * Só a soltura que veio depois de o painel andar o assenta: a rolagem de um filho que acaba no começo, sem arraste
 * sobrando, não deve fechar a conversa.
 */
private class PanelNestedScroll(
    private val state: AnchoredDraggableState<PanelValue>,
    private val fling: TargetedFlingBehavior,
) : NestedScrollConnection {
    private var moved = false

    private val scrollScope = object : ScrollScope {
        override fun scrollBy(pixels: Float): Float = state.dispatchRawDelta(pixels)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.x == 0f || state.offset.isNaN()) return Offset.Zero
        val used = state.dispatchRawDelta(available.x)
        if (used != 0f) moved = true
        return Offset(used, 0f)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!moved) return Velocity.Zero
        moved = false
        val remaining = with(fling) { scrollScope.performFling(available.x) }
        return Velocity(available.x - remaining, 0f)
    }
}
