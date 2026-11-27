package chat.papo.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import chat.papo.mobile.data.api.OutgoingFile
import chat.papo.mobile.data.api.PapoClient
import chat.papo.mobile.media.ImageReducer
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
     * Toca [media] a partir de [startAtMs] e desenha só a superfície do vídeo, inteiro dentro de [modifier] e sobre preto: os
     * controles são da tela comum, que lê e comanda o player pelo [state]. Os pedidos saem pelo `http` de [client], que deve
     * ser o da conta dona da mensagem: dele vêm o cookie `Auth` e a renovação única da sessão. Nada vai para o disco. O player
     * nasce com esta composição e é liberado quando ela sai (travar o app também a tira). Fora da tela ele pausa e não
     * retoma sozinho.
     *
     * Enquanto o player está na composição, [state] diz se ele toca, carrega ou terminou, o ponto, a duração e o trecho já
     * baixado, e executa tocar, pausar e buscar; ao sair, o [state] volta ao de um player novo. Um [state] acompanha um player
     * por vez. O player não toca no vídeo: o toque é de quem está por cima dele.
     *
     * [onPosition] recebe a posição atual em milissegundos, para a tela guardá-la e voltar ao mesmo ponto depois de girar o
     * aparelho. [onFailure] recebe o motivo uma vez, depois de a política de repetição (`retryDelay`) se esgotar; o texto e as
     * ações do erro são da tela comum. [startAtMs] só vale quando o player nasce.
     */
    @Composable
    fun VideoPlayer(
        client: PapoClient,
        media: PlayableMedia,
        startAtMs: Long,
        modifier: Modifier,
        state: VideoPlayerState,
        onPosition: (Long) -> Unit,
        onFailure: (PlaybackFailure) -> Unit,
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
     * Devolve a ação que abre o seletor de fotos do sistema só com imagens, que dispensa a permissão de armazenamento, para
     * escolher o avatar ou o banner. [onPicked] recebe a imagem escolhida descrita como um [OutgoingFile] (nada copiado);
     * se o usuário desiste, não é chamado.
     */
    @Composable
    fun rememberImagePicker(onPicked: (OutgoingFile) -> Unit): () -> Unit

    /** Quem prepara a imagem escolhida para o perfil, em memória e fora da thread principal. */
    val imageReducer: ImageReducer

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

    // 02 · trilha A · onda 2 (visualizador)

    /**
     * Abre a folha de compartilhamento do sistema com [bytes] como um arquivo chamado [name], do tipo [mimeType]. O
     * conteúdo vai da memória para o aplicativo escolhido, sem arquivo em disco, e só para quem o sistema autorizar
     * naquele compartilhamento: cada aplicativo lê uma vez, e o que não foi lido some em poucos minutos ou quando o app
     * trava. Devolve false se não foi possível abrir a folha (nenhum aplicativo atende). Não altera [bytes], que pode ser
     * o array do cache de mídia.
     */
    fun shareMedia(name: String, mimeType: String, bytes: ByteArray): Boolean

    // 02 · trilha B · onda 3 (teclado)

    /**
     * O modificador que faz um campo de texto de `TextFieldState` receber, pelo teclado (GIF, figurinha, imagem) e ao colar,
     * imagens e vídeos como anexos: cada um chega descrito como um [OutgoingFile], com o conteúdo já lido (até
     * [RECEIVED_MEDIA_MAX_BYTES] cada), porque o acesso que o teclado dá ao conteúdo é temporário. Passa para [onMedia] só o
     * que é imagem ou vídeo; o resto (texto colado) fica para o campo. Um item que não pôde ser lido, ou que passa do limite,
     * chega com [OutgoingFile.unavailable]. Roda na thread principal, dentro da entrega do conteúdo.
     */
    fun receiveMedia(onMedia: (List<OutgoingFile>) -> Unit): Modifier

    // 0.9.0 · item 14 (links externos)

    /**
     * Abre [url] fora do app (no navegador ou no app que o sistema escolher). Só abre o que [chat.papo.mobile.core.safeExternalUrl] aceita
     * (`http` e `https`, regra 6); qualquer outro esquema não abre nada. Devolve false quando não abriu: link recusado ou
     * nenhum app para abri-lo.
     */
    fun openExternalUrl(url: String): Boolean
}

/** Tamanho máximo de cada imagem ou vídeo recebido pelo teclado ou colado: ele fica na memória até ser enviado ou tirado. */
const val RECEIVED_MEDIA_MAX_BYTES = 20 * 1024 * 1024

val LocalPlatformUi = staticCompositionLocalOf<PlatformUi> { error("PlatformUi não fornecido") }
