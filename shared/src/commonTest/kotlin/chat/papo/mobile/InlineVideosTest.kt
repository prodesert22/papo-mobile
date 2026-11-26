package chat.papo.mobile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import chat.papo.mobile.ui.media.InlineVideos
import chat.papo.mobile.ui.media.PlayingVideo
import chat.papo.mobile.ui.media.VideoHost
import chat.papo.mobile.ui.media.cardKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Quem toca na conversa: o vídeo do cartão e o da tela cheia, um por vez, sem desenhar nada. */
class InlineVideosTest {
    // InlineVideos

    @Test
    fun aConversaComecaSemNenhumVideoTocando() {
        val videos = InlineVideos()

        assertNull(videos.inline)
        assertNull(videos.fullscreen)
    }

    @Test
    fun oPlayDoCartaoTocaDoComecoEDeixaATelaCheiaFechada() {
        val videos = InlineVideos().playInline("attachment:a1")

        assertEquals("attachment:a1", videos.inline?.key)
        assertEquals(0L, videos.inline?.positionMs)
        assertNull(videos.fullscreen)
    }

    @Test
    fun tocarOutroCartaoTiraOAnterior() {
        val first = InlineVideos().playInline("attachment:a1")

        val second = first.playInline("embed:e1")

        assertEquals("embed:e1", second.inline?.key)
        assertNull(second.fullscreen)
    }

    @Test
    fun tocarForaDoPlayAbreATelaCheiaDoComecoSemPlayerNoCartao() {
        val videos = InlineVideos().openFullscreen("attachment:a1")

        assertNull(videos.inline)
        assertEquals("attachment:a1", videos.fullscreen?.key)
        assertEquals(0L, videos.fullscreen?.positionMs)
    }

    @Test
    fun aTelaCheiaDoCartaoLevaOPontoETiraOPlayerDele() {
        val playing = InlineVideos().playInline("attachment:a1")
        playing.inline!!.positionMs = 42_000

        val videos = playing.openFullscreen("attachment:a1", playing.inline.positionMs)

        assertNull(videos.inline)
        assertEquals("attachment:a1", videos.fullscreen?.key)
        assertEquals(42_000L, videos.fullscreen?.positionMs)
    }

    @Test
    fun umPontoNegativoComecaDoZero() {
        assertEquals(0L, InlineVideos().openFullscreen("attachment:a1", -5).fullscreen?.positionMs)
    }

    @Test
    fun fecharATelaCheiaVoltaACapaSemDevolverOPlayerAoCartao() {
        val videos = InlineVideos().playInline("attachment:a1").openFullscreen("attachment:a1", 9_000).closeFullscreen()

        assertNull(videos.inline)
        assertNull(videos.fullscreen)
    }

    @Test
    fun tocarNoCartaoComATelaCheiaAbertaFechaATelaCheia() {
        val videos = InlineVideos().openFullscreen("attachment:a1", 9_000).playInline("embed:e1")

        assertEquals("embed:e1", videos.inline?.key)
        assertNull(videos.fullscreen)
    }

    @Test
    fun oCartaoQueSaiDeCenaLiberaOVideoQueTocava() {
        val playing = InlineVideos().playInline("attachment:a1")

        val released = playing.release(playing.inline!!)

        assertNull(released.inline)
    }

    @Test
    fun umCartaoQueJaNaoEODonoNaoLiberaOVideoDeOutro() {
        val first = InlineVideos().playInline("attachment:a1")
        val stale = first.inline!!
        val second = first.playInline("embed:e1")

        // O cartão do primeiro sai de cena depois de o segundo tocar: o segundo continua.
        assertSame(second, second.release(stale))
    }

    @Test
    fun oCartaoQueSaiDeCenaDepoisDaTelaCheiaNaoMexeNela() {
        val playing = InlineVideos().playInline("attachment:a1")
        val fullscreen = playing.openFullscreen("attachment:a1", 9_000)

        val released = fullscreen.release(playing.inline!!)

        assertEquals("attachment:a1", released.fullscreen?.key)
        assertEquals(9_000L, released.fullscreen?.positionMs)
    }

    // Saver

    private val saverScope = SaverScope { true }

    private fun save(videos: InlineVideos): Any? = with(InlineVideos.Saver) { saverScope.save(videos) }

    @Test
    fun asDuasChavesEOsPontosVoltamDepoisDeGirar() {
        val saved = InlineVideos(PlayingVideo("attachment:a1", 7_000), PlayingVideo("embed:e1", 12_000))

        val restored = InlineVideos.Saver.restore(assertNotNull(save(saved)))

        assertNotNull(restored)
        assertEquals("attachment:a1", restored.inline?.key)
        assertEquals(7_000L, restored.inline?.positionMs)
        assertEquals("embed:e1", restored.fullscreen?.key)
        assertEquals(12_000L, restored.fullscreen?.positionMs)
    }

    @Test
    fun soOCartaoSobreviveAGirarSemTelaCheia() {
        val restored = InlineVideos.Saver.restore(assertNotNull(save(InlineVideos().playInline("attachment:a1"))))

        assertEquals("attachment:a1", restored?.inline?.key)
        assertNull(restored?.fullscreen)
    }

    @Test
    fun oYouTubeNaoSobreviveAGirarNemAMatarOProcessoPoisSoCarregaDepoisDeUmToque() {
        val saved = InlineVideos(PlayingVideo(cardKey("m1", "youtube:aB3dE5gH7jK"), 7_000), PlayingVideo("youtube:aB3dE5gH7jK", 12_000))

        val restored = InlineVideos.Saver.restore(assertNotNull(save(saved)))

        assertNull(restored?.inline)
        assertNull(restored?.fullscreen)
    }

    @Test
    fun oVideoDoServidorSobreviveMesmoComOYouTubeNoOutroLugar() {
        val saved = InlineVideos(PlayingVideo(cardKey("m1", "youtube:aB3dE5gH7jK"), 7_000), PlayingVideo("embed:e1", 12_000))

        val restored = InlineVideos.Saver.restore(assertNotNull(save(saved)))

        assertNull(restored?.inline)
        assertEquals("embed:e1", restored?.fullscreen?.key)
        assertEquals(12_000L, restored?.fullscreen?.positionMs)
    }

    @Test
    fun semNadaTocandoOEstadoRestauradoEstaVazio() {
        val restored = InlineVideos.Saver.restore(assertNotNull(save(InlineVideos())))

        assertNull(restored?.inline)
        assertNull(restored?.fullscreen)
    }

    @Test
    fun umEstadoSalvoIlegivelNaoDerrubaATela() {
        val restored = InlineVideos.Saver.restore("lixo")

        assertNull(restored?.inline)
        assertNull(restored?.fullscreen)
    }

    // VideoHost

    @Test
    fun aChaveDoCartaoJuntaAMensagemEAMidia() {
        assertEquals("m1/embed:e1", cardKey("m1", "embed:e1"))
    }

    @Test
    fun oHostSoDizQueOVideoTocaNoCartaoDaChaveCerta() {
        val host = VideoHost(mutableStateOf(InlineVideos()))
        host.playInline(cardKey("m1", "attachment:a1"))

        assertEquals("m1/attachment:a1", host.inline(cardKey("m1", "attachment:a1"))?.key)
        assertNull(host.inline(cardKey("m1", "embed:e1")))
    }

    @Test
    fun oLinkRepetidoEmDuasMensagensSoTocaNoCartaoQueRecebeuOToque() {
        // O servidor reaproveita o embed de um link entre as mensagens: a mídia é a mesma, os cartões não.
        val host = VideoHost(mutableStateOf(InlineVideos()))

        host.playInline(cardKey("m2", "embed:e1"))

        assertNull(host.inline(cardKey("m1", "embed:e1")))
        assertNotNull(host.inline(cardKey("m2", "embed:e1")))
    }

    @Test
    fun oHostLevaOPontoDoCartaoParaATelaCheiaDaMidia() {
        val state = mutableStateOf(InlineVideos())
        val host = VideoHost(state)
        host.playInline(cardKey("m1", "attachment:a1"))
        val playing = assertNotNull(host.inline(cardKey("m1", "attachment:a1")))
        playing.positionMs = 42_000

        host.openFullscreen("attachment:a1", playing.positionMs)

        assertNull(host.inline(cardKey("m1", "attachment:a1")))
        assertEquals("attachment:a1", state.value.fullscreen?.key)
        assertEquals(42_000L, state.value.fullscreen?.positionMs)
    }

    @Test
    fun oHostTrocaOVideoDoCartaoEOCartaoAntigoNaoOTiraDeVolta() {
        val host = VideoHost(mutableStateOf(InlineVideos()))
        host.playInline(cardKey("m1", "attachment:a1"))
        val first = assertNotNull(host.inline(cardKey("m1", "attachment:a1")))

        host.playInline(cardKey("m2", "embed:e1"))
        host.release(first)

        assertNull(host.inline(cardKey("m1", "attachment:a1")))
        assertNotSame(first, host.inline(cardKey("m2", "embed:e1")))
        assertEquals("m2/embed:e1", host.inline(cardKey("m2", "embed:e1"))?.key)
    }

    @Test
    fun oHostLiberaOVideoDoCartaoQueSaiuDeCena() {
        val host = VideoHost(mutableStateOf(InlineVideos()))
        host.playInline(cardKey("m1", "attachment:a1"))

        host.release(assertNotNull(host.inline(cardKey("m1", "attachment:a1"))))

        assertNull(host.inline(cardKey("m1", "attachment:a1")))
    }
}
