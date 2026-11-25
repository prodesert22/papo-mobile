package chat.papo.mobile.platform

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.media3.ui.PlayerView
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.PlaybackFailure
import kotlinx.coroutines.delay

/** De quanto em quanto tempo a posição vai para a tela enquanto o vídeo toca. */
private const val POSITION_INTERVAL_MS = 1_000L

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
 * O player de vídeo do Android: um `ExoPlayer` por composição, com os controles do `PlayerView`.
 * O contrato está em `PlatformUi.VideoPlayer`.
 *
 * O player vale para o par ([client], [media]); [startAtMs] só conta na primeira composição, porque
 * a tela devolve a posição que recebe de [onPosition] e ela não pode reiniciar o vídeo.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun AndroidVideoPlayer(
    client: PapoClient,
    media: PlayableMedia,
    startAtMs: Long,
    modifier: Modifier,
    onPosition: (Long) -> Unit,
    onFailure: (PlaybackFailure) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnPosition by rememberUpdatedState(onPosition)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val player = remember(client, media.key) { PlayerHolder(context, client) }.player

    DisposableEffect(player) {
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
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(media.url), startAtMs.coerceAtLeast(0L))
        player.playWhenReady = true

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
        }
    }

    // A posição vai para a tela a cada segundo, para um giro de aparelho voltar ao mesmo ponto.
    LaunchedEffect(player) {
        while (true) {
            delay(POSITION_INTERVAL_MS)
            if (player.isPlaying) currentOnPosition(player.currentPosition)
        }
    }

    HideSystemBarsInDialog()

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                // Um vídeo só: anterior e próximo não levam a lugar nenhum.
                setShowPreviousButton(false)
                setShowNextButton(false)
            }
        },
        update = { view -> view.player = player },
        // O dono do player o libera; a view só precisa largá-lo antes.
        onRelease = { view -> view.player = null },
    )
}

/**
 * Esconde as barras do sistema na janela do diálogo que contém o player (tela cheia de verdade),
 * com o gesto de deslizar da borda para vê-las de passagem. Fora de um diálogo não faz nada: a
 * janela do app é da tela de conversa, não do player.
 */
@Composable
private fun HideSystemBarsInDialog() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
