package chat.papo.mobile.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.papo.mobile.media.formatClock
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.video_back_10
import chat.papo.mobile.resources.video_controls_toggle
import chat.papo.mobile.resources.video_forward_10
import chat.papo.mobile.resources.video_loading
import chat.papo.mobile.resources.video_pause
import chat.papo.mobile.resources.video_play
import chat.papo.mobile.resources.video_position
import chat.papo.mobile.resources.video_replay
import chat.papo.mobile.resources.video_time
import chat.papo.mobile.ui.components.VideoPlayerState
import chat.papo.mobile.ui.theme.LocalPapoColors
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** De quanto os botões de voltar e avançar andam o vídeo. */
internal const val VIDEO_SKIP_MS = 10_000L

/** O que o botão do meio dos controles faz agora; com [Loading] não há botão, e sim o indicador de carregamento. */
internal enum class CenterAction { Loading, Replay, Pause, Play }

/** O botão do meio: de novo no fim do vídeo, o indicador enquanto carrega, pausar se quer tocar e tocar se está pausado. */
internal fun centerAction(player: VideoPlayerState): CenterAction = when {
    player.ended -> CenterAction.Replay
    player.loading -> CenterAction.Loading
    player.playWhenReady -> CenterAction.Pause
    else -> CenterAction.Play
}

/** [value] como fração de [total], de 0 a 1; sem [total] (duração ainda desconhecida), 0. */
internal fun fractionOf(value: Long, total: Long): Float = if (total <= 0L) 0f else (value.toFloat() / total).coerceIn(0f, 1f)

/*
 * A cor de destaque dos controles é a `accent` do app (`LocalPapoColors`), e não a `primary` do esquema: no tema escuro as duas
 * são o mesmo dourado, mas no claro a `primary` é o azul-petróleo, que some sobre o escurecido que fica atrás dos controles
 * (o vídeo é escuro nos dois temas). Texto e ícones sobre ela usam a `onAccent`.
 */

/** O escurecido sobre o vídeo que dá fundo aos botões redondos: legível sobre qualquer quadro nos dois temas. */
private val ButtonScrim = Color.Black.copy(alpha = 0.45f)

/** O fundo das barras de cima e de baixo: do escuro na borda até nada, para o texto e a barra lerem sobre qualquer vídeo. */
internal fun edgeScrim(fromTop: Boolean): Brush {
    val edge = Color.Black.copy(alpha = 0.72f)
    return Brush.verticalGradient(if (fromTop) listOf(edge, Color.Transparent) else listOf(Color.Transparent, edge))
}

/** A área de toque do marcador da barra; o ponto desenhado é menor, e cresce enquanto o dedo está nela. */
private val ThumbArea = 24.dp

/** O tempo com algarismos tabulares: todos têm a mesma largura e o texto não treme enquanto os segundos passam. */
@Composable
private fun timeStyle(): TextStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")

/**
 * Os controles do vídeo, sobre o player: no meio voltar e avançar [VIDEO_SKIP_MS] com o botão de tocar e pausar entre
 * eles, e embaixo a barra de progresso com o tempo, sobre um degradê escuro. Preenche a área do vídeo. Um toque em qualquer
 * ponto dela mostra ou esconde os controles (ver [VideoControlsVisibility]); a busca só acontece ao soltar a barra.
 *
 * A barra de título de quem usa os controles acompanha [visibility]: ela não faz parte daqui porque cada tela tem a sua.
 */
@Composable
internal fun VideoControls(player: VideoPlayerState, visibility: VideoControlsVisibility, modifier: Modifier = Modifier) {
    Box(modifier) {
        val toggleLabel = stringResource(Res.string.video_controls_toggle)
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = toggleLabel,
                    role = Role.Button,
                    onClick = visibility::tap,
                ),
        )
        AnimatedVisibility(visible = visibility.visible, modifier = Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                BottomBar(player, visibility, Modifier.align(Alignment.BottomCenter))
                CenterButtons(player, visibility, Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun CenterButtons(player: VideoPlayerState, visibility: VideoControlsVisibility, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        SkipButton(Icons.Filled.Replay10, Res.string.video_back_10) {
            visibility.touch()
            player.seekBy(-VIDEO_SKIP_MS)
        }
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            val action = centerAction(player)
            if (action == CenterAction.Loading) {
                LoadingBadge(Modifier.fillMaxSize())
            } else {
                val (icon, label) = when (action) {
                    CenterAction.Replay -> Icons.Filled.Replay to Res.string.video_replay
                    CenterAction.Pause -> Icons.Filled.Pause to Res.string.video_pause
                    else -> Icons.Filled.PlayArrow to Res.string.video_play
                }
                FilledIconButton(
                    onClick = {
                        visibility.touch()
                        player.togglePlay()
                    },
                    modifier = Modifier.fillMaxSize(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = LocalPapoColors.current.accent,
                        contentColor = LocalPapoColors.current.onAccent,
                    ),
                ) {
                    Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(44.dp))
                }
            }
        }
        SkipButton(Icons.Filled.Forward10, Res.string.video_forward_10) {
            visibility.touch()
            player.seekBy(VIDEO_SKIP_MS)
        }
    }
}

@Composable
private fun SkipButton(icon: ImageVector, label: StringResource, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = ButtonScrim, contentColor = Color.White),
    ) {
        Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(30.dp))
    }
}

/**
 * O indicador de carregamento do vídeo: o anel na cor de destaque do tema sobre um disco escuro, que o deixa legível sobre
 * a capa e sobre qualquer quadro. Ocupa o tamanho que receber.
 */
@Composable
internal fun LoadingBadge(modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.video_loading)
    Box(modifier.clip(CircleShape).background(ButtonScrim), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.fillMaxSize(0.62f).semantics { contentDescription = description },
            color = LocalPapoColors.current.accent,
            strokeWidth = 4.dp,
        )
    }
}

@Composable
private fun BottomBar(player: VideoPlayerState, visibility: VideoControlsVisibility, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(edgeScrim(fromTop = false))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatClock(player.shownPositionMs), style = timeStyle(), color = Color.White)
        SeekBar(player, visibility, Modifier.weight(1f).padding(horizontal = 4.dp))
        Text(formatClock(player.durationMs), style = timeStyle(), color = Color.White.copy(alpha = 0.8f))
    }
}

/**
 * A barra de progresso: o trecho tocado na cor de destaque, o já baixado mais claro e o resto translúcido. Arrastar só
 * muda o ponto mostrado; a busca é uma só, ao soltar ([VideoPlayerState.commitScrub]). Com o dedo nela os controles não somem.
 * O `Slider` com marcador e trilha próprios ainda é experimental no Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(player: VideoPlayerState, visibility: VideoControlsVisibility, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val dragged by interactions.collectIsDraggedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val holding = dragged || pressed
    LaunchedEffect(holding) { visibility.holding = holding }
    DisposableEffect(visibility) { onDispose { visibility.holding = false } }

    val durationMs = player.durationMs
    val shownMs = player.shownPositionMs
    val end = durationMs.coerceAtLeast(1L).toFloat()
    val played = fractionOf(shownMs, durationMs)
    val buffered = fractionOf(player.bufferedMs, durationMs)
    val description = stringResource(Res.string.video_position)
    val time = stringResource(Res.string.video_time, formatClock(shownMs), formatClock(durationMs))
    Slider(
        value = shownMs.toFloat().coerceIn(0f, end),
        onValueChange = {
            visibility.touch()
            player.scrubTo(it.toLong())
        },
        modifier = modifier.semantics {
            contentDescription = description
            stateDescription = time
        },
        // Sem duração (o player ainda não abriu o vídeo) não há para onde buscar.
        enabled = durationMs > 0L,
        valueRange = 0f..end,
        onValueChangeFinished = player::commitScrub,
        interactionSource = interactions,
        thumb = { SeekThumb(holding) },
        track = { SeekTrack(played, buffered) },
    )
}

@Composable
private fun SeekThumb(active: Boolean) {
    val size by animateDpAsState(if (active) 20.dp else 14.dp, label = "seekThumb")
    Box(Modifier.size(ThumbArea), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).background(LocalPapoColors.current.accent))
    }
}

/** A trilha tem a largura que o `Slider` dá: ele já a descontou do marcador, então o ponto cai onde a fração manda. */
@Composable
private fun SeekTrack(played: Float, buffered: Float, height: Dp = 4.dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(Color.White.copy(alpha = 0.28f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(buffered).background(Color.White.copy(alpha = 0.5f)))
        Box(Modifier.fillMaxHeight().fillMaxWidth(played).background(LocalPapoColors.current.accent))
    }
}
