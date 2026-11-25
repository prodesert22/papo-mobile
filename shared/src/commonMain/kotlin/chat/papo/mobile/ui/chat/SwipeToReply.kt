package chat.papo.mobile.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Quanto a mensagem precisa andar para o gesto valer: ao passar daqui o aparelho vibra, e soltar responde. */
private val ReplyThreshold = 64.dp

/** Até onde a mensagem acompanha o dedo. */
private val MaxPull = 96.dp

/** O ícone de responder: o tamanho, e a folga entre ele e o fim da mensagem (no limiar ele fica centrado na faixa revelada). */
private val IconSize = 32.dp
private val IconGap = 16.dp

/**
 * Deslizar para o lado do fim da linha (a esquerda, ou a direita em idiomas da direita para a esquerda)
 * puxa [content] e revela o ícone de responder; soltar depois do limiar chama [onReply], e a linha volta
 * sozinha. Só o movimento horizontal conta: a rolagem vertical da lista segue livre, porque o gesto
 * só começa depois que o dedo passa da folga de toque no sentido horizontal.
 *
 * Quem começa perto da borda da tela não responde: ali o sistema usa o deslize para voltar
 * (a área vem de [WindowInsets.Companion.systemGestures], e é vazia com a navegação por botões).
 */
@Composable
fun SwipeToReply(onReply: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val haptics = LocalHapticFeedback.current
    val gestureInsets = WindowInsets.systemGestures
    val thresholdPx = with(density) { ReplyThreshold.toPx() }
    val maxPx = with(density) { MaxPull.toPx() }
    // Leitura só na camada de layout e de desenho: acompanhar o dedo não recompõe a mensagem.
    var pull by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    // O arrasto começou fora das bordas do sistema? Senão ele é ignorado, e quem o usa é o gesto de voltar.
    var tracking by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    val reply by rememberUpdatedState(onReply)
    // +1 anda para a direita (idiomas da direita para a esquerda), -1 para a esquerda.
    val toward = if (direction == LayoutDirection.Rtl) 1f else -1f

    val dragState = rememberDraggableState { delta ->
        if (!tracking) return@rememberDraggableState
        pull = (pull + delta * toward).coerceIn(0f, maxPx)
        val reached = pull >= thresholdPx
        if (reached && !armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        armed = reached
    }

    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { start ->
                    val left = gestureInsets.getLeft(density, direction)
                    val right = gestureInsets.getRight(density, direction)
                    tracking = start.x >= left && start.x <= widthPx - right
                },
                onDragStopped = {
                    val replied = tracking && armed
                    tracking = false
                    armed = false
                    if (replied) reply()
                    animate(pull, 0f, animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)) { value, _ -> pull = value }
                },
            ),
    ) {
        val reached = armed
        // `absoluteOffset`: o `offset` espelha em idiomas da direita para a esquerda, e o sentido de `toward` já é o da tela.
        // O ícone dá a esta caixa a altura mínima de `IconSize`; `propagateMinConstraints` faz o conteúdo ocupá-la inteira,
        // senão o fundo e a barra de uma mensagem curta acabariam acima do fim da caixa, deixando uma faixa vazia.
        Box(
            Modifier.absoluteOffset { IntOffset((pull * toward).roundToInt(), 0) }.heightIn(min = IconSize),
            propagateMinConstraints = true,
        ) {
            content()
            // O ícone anda junto com a mensagem, logo depois do fim dela: nunca fica por cima do texto.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = IconGap)
                    .size(IconSize)
                    .graphicsLayer {
                        val progress = (pull / thresholdPx).coerceIn(0f, 1f)
                        alpha = progress
                        scaleX = 0.6f + 0.4f * progress
                        scaleY = scaleX
                    }
                    .clip(CircleShape)
                    .background(if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (reached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
