package chat.papo.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.voice.VideoTrackHandle

/** O que a interface precisa da plataforma e não existe no Compose comum. */
interface PlatformUi {
    /** Devolve a ação que abre o seletor de arquivos do sistema. */
    @Composable
    fun rememberFilePicker(onPicked: (List<OutgoingFile>) -> Unit): () -> Unit

    /** Desenha uma faixa de vídeo de chamada. [fill] recorta para preencher; senão mostra inteira. */
    @Composable
    fun VideoView(track: VideoTrackHandle, modifier: Modifier, fill: Boolean)

    /**
     * Toca [media] com os controles do sistema, a partir de [startAtMs]. Os pedidos saem pelo
     * `http` de [client], que deve ser o da conta dona da mensagem: dele vêm o cookie `Auth` e a
     * renovação única da sessão. Nada vai para o disco. O player nasce com esta composição e é
     * liberado quando ela sai (travar o app também a tira). Fora da tela ele pausa e não retoma
     * sozinho.
     *
     * [onPosition] recebe a posição atual em milissegundos, para a tela guardá-la e voltar ao
     * mesmo ponto depois de girar o aparelho. [onFailure] recebe o motivo uma vez, depois de a
     * política de repetição (`retryDelay`) se esgotar; o texto e as ações do erro são da tela comum.
     * [onControlsVisible] diz quando os controles do player aparecem e somem, para a tela esconder
     * junto o que ela desenha por cima do vídeo.
     *
     * Com [onFullscreen] os controles ganham o botão de tela cheia, que o chama: é o player do cartão da mensagem, e quem
     * o chama o tira dali e abre o vídeo no visualizador. Null (a tela cheia, que já é o visualizador) não tem o botão.
     * Só vale quando o player nasce: a nulidade não muda durante a vida dele.
     */
    @Composable
    fun VideoPlayer(
        client: PapoClient,
        media: PlayableMedia,
        startAtMs: Long,
        modifier: Modifier,
        onPosition: (Long) -> Unit,
        onFailure: (PlaybackFailure) -> Unit,
        onControlsVisible: (Boolean) -> Unit = {},
        onFullscreen: (() -> Unit)? = null,
    )

    /**
     * Esconde as barras do sistema (de estado e de navegação) enquanto esta composição estiver na tela, com o gesto
     * de deslizar da borda para vê-las de passagem; elas voltam quando a composição sai. Vale para a janela do
     * diálogo que contém a chamada: fora de um diálogo não faz nada, porque a janela do app não é de quem chama.
     */
    @Composable
    fun HideSystemBars()

    /**
     * A fonte de emojis do aparelho sabe desenhar [emoji]? Versões antigas do Android
     * não têm os emojis mais novos e mostrariam um quadrado vazio. Pode ser lento:
     * chame fora da thread principal.
     */
    fun supportsEmoji(emoji: String): Boolean

    /** Grava um arquivo na pasta de downloads do usuário. */
    suspend fun saveToDownloads(name: String, mimeType: String, bytes: ByteArray): Boolean

    /**
     * Decodifica [bytes] em memória, sem criar arquivo, e reduz o lado maior a [maxPx] (nunca amplia).
     * Com [animate] uma imagem animada anima; sem ele, ou com "remover animações" ligado no sistema,
     * devolve o primeiro quadro. Null quando [bytes] não é uma imagem. Roda fora da thread principal,
     * não altera [bytes] (o `ByteArray` do cache é compartilhado) e não deixa nada aberto se for cancelada.
     */
    suspend fun decodeImage(bytes: ByteArray, maxPx: Int, animate: Boolean): PlatformImage?

    /**
     * Devolve a ação que abre o seletor de fotos e vídeos do sistema, que dispensa a permissão de armazenamento.
     * O seletor deixa escolher até [maxItems] itens, mas a tela confere o que volta: um sistema sem o seletor de
     * fotos usa o de documentos, que ignora o limite, e o seletor pede ao menos dois itens mesmo com uma vaga só.
     * Cada item escolhido chega descrito como um [OutgoingFile], com o nome, o tipo e o tamanho que o provedor
     * informa e sem nada copiado; um item que o provedor já recusou chega com [OutgoingFile.unavailable].
     */
    @Composable
    fun rememberMediaPicker(maxItems: Int, onPicked: (List<OutgoingFile>) -> Unit): () -> Unit

    /**
     * O player do YouTube embutido, que toca o vídeo [videoId] (um id já validado por `isYouTubeId`) assim que entra na
     * composição. Só entra depois do toque do usuário no ▶, que é o consentimento: antes disso nada é pedido ao YouTube.
     * Preenche [modifier] e mostra os controles do próprio YouTube; o dono é a composição, que o fecha (e apaga o que ele
     * guardou) quando sai dela, e o app o fecha também ao travar.
     *
     * Com [fullscreenButton] o botão de tela cheia do YouTube leva o mesmo player para a tela cheia, sem recarregar (é o do
     * cartão da mensagem); sem ele (a tela cheia do app) o botão não existe. Qualquer navegação que o player tente fora da
     * página do vídeo não acontece nele: chama [onOpenExternally], que abre o vídeo no YouTube fora do app.
     */
    @Composable
    fun YouTubePlayer(videoId: String, modifier: Modifier, fullscreenButton: Boolean, onOpenExternally: () -> Unit)
}

val LocalPlatformUi = staticCompositionLocalOf<PlatformUi> { error("PlatformUi não fornecido") }
