package chat.papo.mobile.platform

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ktor.KtorDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.ui.components.VideoPlayerCommands
import chat.papo.mobile.ui.components.VideoPlayerState
import kotlinx.coroutines.delay

/** De quanto em quanto tempo a posição vai para a tela guardá-la (girar o aparelho) enquanto o vídeo toca. */
private const val POSITION_INTERVAL_MS = 1_000L

/** De quanto em quanto tempo o [VideoPlayerState] recebe o ponto e o trecho baixado enquanto o vídeo toca: a barra anda sem saltos. */
private const val STATE_INTERVAL_MS = 250L

/**
 * Fonte de dados dos players: os pedidos saem pelo `http` do [client], o da conta dona do vídeo.
 * Dele vêm o cookie `Auth` (regra 4), a única renovação depois de um 401 e o motor, o mesmo OkHttp
 * do resto do app (regra 16). Sem `CacheDataSource`: nada vai para o disco (regra 8). O `Range` sai
 * da posição que o player pede, e um 416 no fim do arquivo é uma leitura vazia, não um erro.
 */
@OptIn(UnstableApi::class)
fun papoDataSourceFactory(client: PapoClient): DataSource.Factory = KtorDataSource.Factory(client.http)

/**
 * Dono do `ExoPlayer` de uma composição: nasce quando o `remember` entra na composição e é
 * liberado ao sair dela, inclusive se a composição for abandonada antes de aplicada (regra 7).
 */
@OptIn(UnstableApi::class)
private class PlayerHolder(context: Context, client: PapoClient) : RememberObserver {
    val player: ExoPlayer = ExoPlayer.Builder(
        context.applicationContext,
        DefaultMediaSourceFactory(papoDataSourceFactory(client)).setLoadErrorHandlingPolicy(PapoLoadErrorPolicy),
    )
        // Vídeo é mídia: pede o foco de áudio (pausa quando outro app toma o som, como uma chamada)
        // e pausa sozinho se o fone for desconectado.
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    override fun onRemembered() = Unit

    override fun onForgotten() = player.release()

    override fun onAbandoned() = player.release()
}

/**
 * Escreve em [state] o que o [player] diz de si. "Carregando" é querer tocar sem ter dados (abrindo ou esperando o que baixar):
 * pausado, o player não está carregando para quem olha, ainda que baixe adiante.
 */
private fun VideoPlayerState.publish(player: Player) {
    val playbackState = player.playbackState
    val wantsToPlay = player.playWhenReady
    update(
        playWhenReady = wantsToPlay,
        loading = wantsToPlay && (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE),
        ended = playbackState == Player.STATE_ENDED,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
        bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
    )
}

/**
 * O player de vídeo do Android: um `ExoPlayer` por composição, que desenha só a superfície do vídeo; os controles são da
 * tela comum, que fala com o player pelo [state]. O contrato está em `PlatformUi.VideoPlayer`.
 *
 * O player vale para o par ([client], [media]); [startAtMs] só conta na primeira composição, porque a tela devolve a posição
 * que recebe de [onPosition] e ela não pode reiniciar o vídeo.
 *
 * A superfície é uma `TextureView`, e não a `SurfaceView` que o Media3 usa por padrão: o cartão do chat tem cantos
 * arredondados e fica numa `LazyColumn`, e o visualizador passa as páginas num `HorizontalPager`. A `SurfaceView` é uma
 * janela à parte, que não recorta em cantos arredondados nem acompanha a rolagem quadro a quadro; a `TextureView` é uma
 * view comum, recortada e movida como o resto. O custo é uma cópia a mais por quadro na GPU.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun AndroidVideoPlayer(
    client: PapoClient,
    media: PlayableMedia,
    startAtMs: Long,
    modifier: Modifier,
    state: VideoPlayerState,
    onPosition: (Long) -> Unit,
    onFailure: (PlaybackFailure) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnPosition by rememberUpdatedState(onPosition)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val player = remember(client, media.key) { PlayerHolder(context, client) }.player

    DisposableEffect(player, state) {
        fun reportPosition() {
            // Terminado, o próximo ponto de partida é o começo; ficar no fim reabriria um vídeo parado.
            currentOnPosition(if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition)
        }

        var failed = false
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // O player só chega aqui depois da política de repetição: o motivo é definitivo.
                if (failed) return
                failed = true
                currentOnFailure(failureOf(error.cause, error.errorCode))
            }

            override fun onEvents(source: Player, events: Player.Events) = state.publish(source)

            override fun onRenderedFirstFrame() {
                state.hasPicture = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) reportPosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) reportPosition()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) reportPosition()
            }
        }
        val commands = object : VideoPlayerCommands {
            override fun play() {
                // Parado pelo ciclo de vida (`ON_STOP`) o player precisa abrir o vídeo de novo antes de tocar.
                if (!failed && player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }

            override fun pause() = player.pause()

            override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
        }
        state.attach(commands)
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(media.url), startAtMs.coerceAtLeast(0L))
        player.playWhenReady = true
        state.publish(player)

        // O vídeo só carrega com a tela visível. Ao sair, pausa e para de baixar: em segundo plano o
        // sistema derruba a rede do app, e a falha de carga chegaria como erro na volta; a conexão
        // aberta também prenderia uma das poucas vagas do relay do servidor. Voltar não retoma
        // sozinho (`playWhenReady` fica falso): quem retoma é o usuário, do mesmo ponto.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!failed && player.playbackState == Player.STATE_IDLE) player.prepare()
                Lifecycle.Event.ON_STOP -> if (!failed) {
                    player.pause()
                    reportPosition()
                    player.stop()
                }
                else -> Unit
            }
        }
        // Numa tela já iniciada o registro repete ON_START: é ele que dá o primeiro `prepare`.
        lifecycle.addObserver(observer)

        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            state.detach(commands)
        }
    }

    // O ponto vai para o estado em passos curtos, e para a tela guardá-lo a cada segundo, para um giro de aparelho voltar ao mesmo ponto.
    LaunchedEffect(player, state) {
        var sinceReport = 0L
        while (true) {
            delay(STATE_INTERVAL_MS)
            if (!player.isPlaying) continue
            state.publish(player)
            sinceReport += STATE_INTERVAL_MS
            if (sinceReport >= POSITION_INTERVAL_MS) {
                sinceReport = 0L
                currentOnPosition(player.currentPosition)
            }
        }
    }

    // A tela fica acesa enquanto o vídeo quer tocar, como o `PlayerView` fazia sozinho; pausado ou terminado ela volta ao tempo normal.
    val view = LocalView.current
    LaunchedEffect(view, state) {
        snapshotFlow { state.playWhenReady && !state.ended }.collect { view.keepScreenOn = it }
    }
    DisposableEffect(view) { onDispose { view.keepScreenOn = false } }

    // O preto é o fundo do vídeo, que fica inteiro (`Fit`) com tarjas se a caixa tem outra proporção. Antes do primeiro
    // quadro a própria `ContentFrame` cobre a superfície de preto; quem quer outra coisa ali põe por cima (a capa do cartão).
    ContentFrame(
        player = player,
        modifier = modifier.background(Color.Black),
        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        contentScale = ContentScale.Fit,
        artwork = null,
    )
}
