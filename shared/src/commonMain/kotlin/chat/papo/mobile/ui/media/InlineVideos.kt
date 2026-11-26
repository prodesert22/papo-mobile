package chat.papo.mobile.ui.media

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import chat.papo.mobile.chat.isYouTubeKey

/**
 * Os vídeos de uma conversa: o que toca no cartão da mensagem ([inline]) e o que está em tela cheia ([fullscreen]).
 *
 * Só um player existe por vez, porque o servidor limita os pedidos por endereço e o relay tem poucas vagas: tocar um
 * cartão tira o anterior, e abrir a tela cheia tira o do cartão (levando o ponto em que ele estava). É um valor
 * imutável; quem o guarda e o salva é o `ChatScreen`. Fechar a tela cheia não devolve o player ao cartão: ele volta à capa.
 *
 * A chave do [inline] é a do cartão ([cardKey]: a mensagem e a mídia), e a do [fullscreen] é a da mídia: o servidor reaproveita
 * o embed de um link entre mensagens, então duas mensagens com o mesmo link têm a mesma mídia e só uma delas toca no cartão.
 */
internal data class InlineVideos(val inline: PlayingVideo? = null, val fullscreen: PlayingVideo? = null) {
    /** O ▶ do cartão [key] (ver [cardKey]): toca ali, do começo, e some tudo o que tocava antes. */
    fun playInline(key: String) = InlineVideos(inline = PlayingVideo(key))

    /** A tela cheia da mídia [key] a partir de [positionMs] (do cartão que tocava, ou 0 de uma capa); o player do cartão sai. */
    fun openFullscreen(key: String, positionMs: Long = 0L) =
        InlineVideos(fullscreen = PlayingVideo(key, positionMs.coerceAtLeast(0L)))

    fun closeFullscreen() = copy(fullscreen = null)

    /**
     * O cartão que tocava [video] saiu de cena (rolou para fora da lista, a mensagem foi apagada, a conversa fechou): o
     * vídeo para de tocar e perde o ponto. Se o cartão já não é o que toca (outro tomou o lugar, ou foi para a tela cheia),
     * nada muda.
     */
    fun release(video: PlayingVideo) = if (inline === video) copy(inline = null) else this

    companion object {
        /**
         * Sobrevive a girar o aparelho: as duas chaves e os pontos. O vídeo do YouTube fica de fora: ele só carrega depois de
         * um toque no ▶, e um estado restaurado (inclusive depois de o sistema matar o processo) o carregaria sozinho.
         */
        val Saver: Saver<InlineVideos, Any> = Saver(
            save = { slots ->
                with(PlayingVideo.Saver) {
                    listOf(save(slots.inline?.takeUnless { isYouTubeKey(it.key) }), save(slots.fullscreen?.takeUnless { isYouTubeKey(it.key) }))
                }
            },
            restore = { saved ->
                val parts = saved as? List<*>
                InlineVideos(
                    inline = parts?.getOrNull(0)?.let(PlayingVideo.Saver::restore),
                    fullscreen = parts?.getOrNull(1)?.let(PlayingVideo.Saver::restore),
                )
            },
        )
    }
}

/** A chave de um cartão de vídeo: a mensagem em que ele está e a chave da mídia ([chat.papo.mobile.media.PlayableMedia.key]). */
internal fun cardKey(messageId: String, mediaKey: String) = "$messageId/$mediaKey"

/**
 * A ponte entre os cartões de vídeo e o estado da conversa ([InlineVideos]), que mora no `ChatScreen`. Os cartões a leem
 * pela composição de cada um, então só eles recompõem quando o vídeo que toca muda, e não a lista inteira.
 *
 * Abrir a tela cheia passa sempre por [openFullscreen]: é o único ponto por onde um cartão chega ao visualizador.
 */
@Stable
internal class VideoHost(private val state: MutableState<InlineVideos>) {
    /** O vídeo que toca no cartão [key] ([cardKey]) agora, ou null se ele está na capa. */
    fun inline(key: String): PlayingVideo? = state.value.inline?.takeIf { it.key == key }

    /** O ▶ do cartão [key] ([cardKey]). */
    fun playInline(key: String) {
        state.value = state.value.playInline(key)
    }

    /** A tela cheia da mídia [mediaKey], do ponto [positionMs]: tocar fora do ▶, ou o botão de tela cheia do player do cartão. */
    fun openFullscreen(mediaKey: String, positionMs: Long = 0L) {
        state.value = state.value.openFullscreen(mediaKey, positionMs)
    }

    /** O cartão que tocava [video] saiu de cena. */
    fun release(video: PlayingVideo) {
        state.value = state.value.release(video)
    }
}
