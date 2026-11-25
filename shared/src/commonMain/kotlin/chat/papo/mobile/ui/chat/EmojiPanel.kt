package chat.papo.mobile.ui.chat

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import chat.papo.mobile.account.AccountSession
import chat.papo.mobile.chat.EmojiRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** Onde está a gaveta de emoji do compositor. */
enum class EmojiPanelMode {
    Hidden,
    Open,

    /** O teclado está subindo para ocupar o lugar da gaveta, que encolhe junto. */
    ToKeyboard,
}

/**
 * Contas de altura da gaveta, em pixels, sem Compose. "Recolhida" é a altura do teclado
 * (sem a barra de navegação); [extra] é quanto o usuário a puxou acima disso (negativo
 * quando a arrasta para baixo para fechar).
 */
object EmojiPanelMath {
    /**
     * Altura visível. Enquanto o teclado ainda ocupa [keyboardAboveNav] da tela, a gaveta
     * mostra só o que sobra até a altura dele, para o campo não pular na troca. Na busca
     * ela fica com pelo menos [searchHeight] acima do teclado.
     */
    fun visible(
        mode: EmojiPanelMode,
        collapsed: Float,
        extra: Float,
        keyboardAboveNav: Float,
        searching: Boolean,
        searchHeight: Float,
        max: Float,
    ): Float {
        if (mode == EmojiPanelMode.Hidden) return 0f
        val base = collapsed + extra - keyboardAboveNav
        val wanted = if (searching) max(searchHeight, base) else base
        return wanted.coerceIn(0f, max(max, 0f))
    }

    /**
     * Para onde a gaveta vai quando o dedo solta: fechada (`-collapsed`), recolhida (0) ou
     * aberta por inteiro ([expandedExtra]). Um arremesso decide pela direção; sem ele, vale o
     * ponto mais perto. [velocity] positiva é o dedo indo para baixo.
     */
    fun settleTarget(extra: Float, velocity: Float, collapsed: Float, expandedExtra: Float, flingThreshold: Float): Float =
        when {
            velocity < -flingThreshold -> if (extra < 0f) 0f else expandedExtra
            velocity > flingThreshold -> if (extra > 0f) 0f else -collapsed
            else -> listOf(-collapsed, 0f, expandedExtra).minBy { abs(it - extra) }
        }
}

/**
 * Estado da gaveta de emoji que ocupa o lugar do teclado. Abre na altura do teclado;
 * arrastar para cima (pela alça ou rolando a grade) abre por inteiro, e para baixo fecha.
 * Os limites ([collapsed], [expandedExtra]) são atualizados pela tela a cada composição.
 */
@Stable
class EmojiPanelState(private val scope: CoroutineScope) {
    var mode by mutableStateOf(EmojiPanelMode.Hidden)
        private set
    var extra by mutableFloatStateOf(0f)
        private set
    var searchFocused by mutableStateOf(false)

    var collapsed = 0f
    var expandedExtra = 0f
    var flingThreshold = 0f
    private var animation: Job? = null

    val isOpen get() = mode == EmojiPanelMode.Open

    /** Abre no lugar do teclado. Com o teclado fechado ela sobe deslizando. */
    fun open(keyboardVisible: Boolean) {
        animation?.cancel()
        mode = EmojiPanelMode.Open
        if (keyboardVisible) {
            extra = 0f // o teclado descendo já faz a gaveta aparecer aos poucos
        } else {
            extra = -collapsed
            animateTo(0f)
        }
    }

    /** O teclado vai voltar: a gaveta encolhe enquanto ele sobe e some quando ele chega. */
    fun toKeyboard() {
        if (mode == EmojiPanelMode.Hidden) return
        animation?.cancel()
        extra = 0f
        mode = EmojiPanelMode.ToKeyboard
    }

    /** O teclado terminou de subir (ou não vai subir): a gaveta some. */
    fun keyboardArrived() {
        if (mode == EmojiPanelMode.ToKeyboard) close()
    }

    /** Fecha descendo (voltar, arrastar para baixo). */
    fun hide() {
        if (mode == EmojiPanelMode.Hidden) return
        animateTo(-collapsed) { close() }
    }

    private fun close() {
        animation?.cancel()
        mode = EmojiPanelMode.Hidden
        extra = 0f
        searchFocused = false
    }

    /** Arraste de [fingerDelta] pixels (positivo = dedo para baixo). Devolve quanto foi usado. */
    fun dragBy(fingerDelta: Float): Float {
        if (mode != EmojiPanelMode.Open || searchFocused) return 0f
        animation?.cancel()
        val target = (extra - fingerDelta).coerceIn(-collapsed, expandedExtra)
        val used = extra - target
        extra = target
        return used
    }

    /** O dedo soltou com [velocity] (px/s, positiva para baixo): vai para fechada, recolhida ou aberta. */
    fun settle(velocity: Float) {
        if (mode != EmojiPanelMode.Open) return
        val target = EmojiPanelMath.settleTarget(extra, velocity, collapsed, expandedExtra, flingThreshold)
        if (target <= -collapsed) animateTo(-collapsed) { close() } else animateTo(target)
    }

    /** Parada num dos pontos de encaixe? Fora deles, um arremesso termina o arraste da gaveta. */
    private val atRest get() = extra == 0f || extra == expandedExtra

    private fun animateTo(target: Float, then: () -> Unit = {}) {
        animation?.cancel()
        animation = scope.launch {
            animate(extra, target, animationSpec = spring(stiffness = 600f)) { value, _ -> extra = value }
            then()
        }
    }

    /**
     * A grade rola dentro da gaveta: puxar para cima primeiro abre a gaveta por inteiro e só
     * depois rola; puxar para baixo com a grade no topo recolhe e depois fecha.
     */
    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y >= 0f || extra >= expandedExtra) return Offset.Zero
            return Offset(0f, -dragBy(available.y))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f || extra <= -collapsed) return Offset.Zero
            return Offset(0f, -dragBy(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (mode != EmojiPanelMode.Open || atRest) return Velocity.Zero
            settle(available.y)
            return available
        }
    }
}

/**
 * A gaveta em si, logo abaixo do campo de mensagem. [visibleHeightPx] é quanto dela aparece;
 * o conteúdo é montado com [contentHeightPx] e cortado, para a grade não se rearrumar a
 * cada quadro enquanto o teclado entra ou sai.
 */
@Composable
fun EmojiPanel(
    session: AccountSession,
    state: EmojiPanelState,
    visibleHeightPx: Float,
    contentHeightPx: Float,
    onPick: (EmojiRef) -> Unit,
) {
    val density = LocalDensity.current
    val drag = rememberDraggableState { delta -> state.dragBy(delta) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(with(density) { visibleHeightPx.toDp() })
            .clipToBounds(),
    ) {
        EmojiPickerContent(
            session = session,
            onPick = onPick,
            showHandle = true,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .height(with(density) { contentHeightPx.toDp() }),
            headerModifier = Modifier.draggable(
                state = drag,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> state.settle(velocity) },
            ),
            gridModifier = Modifier.nestedScroll(state.nestedScroll),
            onSearchFocusChange = { state.searchFocused = it },
        )
    }
}
