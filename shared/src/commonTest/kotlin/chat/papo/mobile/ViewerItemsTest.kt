package chat.papo.mobile

import androidx.compose.runtime.saveable.SaverScope
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.Message
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.media.playableAttachment
import chat.papo.mobile.ui.media.ViewerItem
import chat.papo.mobile.ui.media.ViewingMedia
import chat.papo.mobile.ui.media.viewerItems
import chat.papo.mobile.ui.media.viewerKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** O que o visualizador de mídia da mensagem decide sem desenhar nada: as páginas, a primeira e o estado que gira com o aparelho. */
class ViewerItemsTest {
    private val server = ServerUrl("https://papo.test")

    private fun image(id: String, mime: String = "image/jpeg", thumbnail: String? = id, status: String? = null) = AttachmentDto(
        id = id,
        mimeType = mime,
        fileName = "$id.jpg",
        sizeBytes = 2_000,
        width = 800,
        height = 600,
        thumbnailId = thumbnail,
        moderationStatus = status,
    )

    private fun video(id: String, status: String? = null) = AttachmentDto(
        id = id,
        mimeType = "video/mp4",
        fileName = "$id.mp4",
        sizeBytes = 9_000,
        width = 640,
        height = 360,
        moderationStatus = status,
    )

    private fun file(id: String, mime: String) = AttachmentDto(id = id, mimeType = mime, fileName = id, sizeBytes = 10)

    private fun message(vararg attachments: AttachmentDto) = Message(
        id = "m1",
        channelId = "c1",
        authorId = "u1",
        content = "",
        createdAt = null,
        createdAtIso = "",
        edited = false,
        replyTo = null,
        webhookId = null,
        externalAuthor = null,
        attachments = attachments.toList(),
        embeds = emptyList(),
        reactions = emptyList(),
        userReactions = emptyList(),
    )

    private fun keysOf(message: Message, tapped: String) = viewerItems(server, message, tapped).items.map { it.key }

    // viewerItems

    @Test
    fun asPaginasSeguemAOrdemDosAnexosEAPrimeiraEAQueFoiTocada() {
        val message = message(image("a"), video("b"), image("c", mime = "image/gif"), image("d"))
        val shown = viewerItems(server, message, viewerKey("c"))
        assertEquals(listOf("attachment:a", "attachment:b", "attachment:c", "attachment:d"), shown.items.map { it.key })
        assertEquals(2, shown.start)
        assertEquals(0, viewerItems(server, message, viewerKey("a")).start)
        assertEquals(3, viewerItems(server, message, viewerKey("d")).start)
    }

    @Test
    fun imagemEGifSaoImagemEVideoLevaAMidiaDoServidorComAMesmaChave() {
        val message = message(image("a", mime = "image/gif"), video("b"))
        val items = viewerItems(server, message, viewerKey("a")).items
        assertIs<ViewerItem.Image>(items[0])
        val clip = assertIs<ViewerItem.Video>(items[1])
        assertEquals(server.endpoint("/attachments/b"), clip.media.url)
        // A chave da página é a que o resto do app dá ao vídeo anexado: é por ela que o cartão pede para abrir naquela página.
        assertEquals(playableAttachment(server, video("b"))?.key, clip.key)
        assertEquals(clip.media.key, clip.key)
    }

    @Test
    fun soOQueOCartaoMostraComoImagemOuComoVideoEntra() {
        val message = message(
            file("doc", "application/pdf"),
            image("semMiniatura", thumbnail = null),
            file("som", "audio/ogg"),
            image("a"),
            video("b"),
        )
        assertEquals(listOf("attachment:a", "attachment:b"), keysOf(message, viewerKey("a")))
    }

    @Test
    fun sensivelQueNaoFoiOTocadoFicaDeFora() {
        val message = message(image("a"), image("b", status = "sensitive"), video("c", status = "sensitive"), image("d"))
        assertEquals(listOf("attachment:a", "attachment:d"), keysOf(message, viewerKey("a")))
        assertEquals(1, viewerItems(server, message, viewerKey("d")).start)
    }

    @Test
    fun sensivelTocadoEntraPorqueOToqueQueOAbreEQueORevelaMasOutroSensivelContinuaFora() {
        val message = message(image("a"), image("b", status = "sensitive"), image("c", status = "sensitive"))
        val shown = viewerItems(server, message, viewerKey("b"))
        assertEquals(listOf("attachment:a", "attachment:b"), shown.items.map { it.key })
        assertEquals(1, shown.start)
        // Um vídeo sensível tocado também entra.
        val clip = message(video("v", status = "sensitive"), image("a"))
        assertEquals(listOf("attachment:v", "attachment:a"), keysOf(clip, viewerKey("v")))
    }

    @Test
    fun bloqueadoFicaDeForaMesmoQuandoETocado() {
        val message = message(image("a"), image("b", status = "blocked"), video("c", status = "blocked"), image("d"))
        val shown = viewerItems(server, message, viewerKey("b"))
        assertEquals(listOf("attachment:a", "attachment:d"), shown.items.map { it.key })
        // O tocado não está entre as páginas: o visualizador abre na primeira.
        assertEquals(0, shown.start)
    }

    @Test
    fun pendingECleanAparecemNormais() {
        val message = message(image("a", status = "pending"), image("b", status = "clean"), image("c", status = "processing"))
        assertEquals(3, viewerItems(server, message, viewerKey("a")).items.size)
    }

    @Test
    fun chaveQueNaoEDeUmaPaginaAbreNaPrimeira() {
        val message = message(image("a"), image("b"))
        assertEquals(0, viewerItems(server, message, "attachment:nenhum").start)
        assertEquals(0, viewerItems(server, message, "embed:e1").start)
        assertEquals(0, viewerItems(server, message, "").start)
    }

    @Test
    fun semMidiaNaoHaPaginas() {
        assertTrue(viewerItems(server, message(), viewerKey("a")).items.isEmpty())
        assertEquals(0, viewerItems(server, message(), viewerKey("a")).start)
        assertTrue(viewerItems(server, message(file("doc", "application/pdf")), viewerKey("doc")).items.isEmpty())
    }

    @Test
    fun videoComIdForaDoFormatoDoServidorNaoEntra() {
        // O id vai no caminho da URL do vídeo: um que sai do servidor da conta não vira página.
        val message = message(image("a"), video("../outra"))
        assertEquals(listOf("attachment:a"), keysOf(message, viewerKey("a")))
    }

    // ViewingMedia

    private val saverScope = SaverScope { true }

    private fun save(viewing: ViewingMedia?): Any? = with(ViewingMedia.Saver) { saverScope.save(viewing) }

    @Test
    fun abrirNumaImagemGuardaAChaveDelaComoAPrimeiraEComoAAtualSemTocarNada() {
        val viewing = ViewingMedia.image("m1", image("a"))
        assertEquals("m1", viewing.messageId)
        assertEquals("attachment:a", viewing.openedKey)
        assertEquals("attachment:a", viewing.currentKey)
        assertFalse(viewing.playing)
    }

    @Test
    fun abrirNumVideoSemPontoMostraACapaEComPontoJaToca() {
        val cover = ViewingMedia.video("m1", "attachment:v")
        assertFalse(cover.playing)
        assertEquals(0L, cover.positionMs)

        val fromStart = ViewingMedia.video("m1", "attachment:v", playFromMs = 0L)
        assertTrue(fromStart.playing)
        assertEquals(0L, fromStart.positionMs)

        val resumed = ViewingMedia.video("m1", "attachment:v", playFromMs = 42_000L)
        assertTrue(resumed.playing)
        assertEquals(42_000L, resumed.positionMs)
        assertEquals("attachment:v", resumed.currentKey)
    }

    @Test
    fun virarAPaginaParaOPlayerEZeraOPonto() {
        val viewing = ViewingMedia.video("m1", "attachment:v", playFromMs = 9_000L)
        viewing.select("attachment:w")
        assertEquals("attachment:w", viewing.currentKey)
        assertFalse(viewing.playing)
        assertEquals(0L, viewing.positionMs)
        // A que abriu a mensagem não muda: é ela que decide qual sensível o visualizador mostra.
        assertEquals("attachment:v", viewing.openedKey)
    }

    @Test
    fun escolherAPaginaQueJaEstaNaTelaNaoMexeNoPlayer() {
        val viewing = ViewingMedia.video("m1", "attachment:v", playFromMs = 9_000L)
        viewing.select("attachment:v")
        assertTrue(viewing.playing)
        assertEquals(9_000L, viewing.positionMs)
    }

    @Test
    fun semMidiaAbertaNadaSeSalva() {
        assertNull(save(null))
    }

    @Test
    fun aMensagemAPaginaEOPontoVoltamDepoisDeGirar() {
        val viewing = ViewingMedia.video("m1", "attachment:v", playFromMs = 1_000L)
        viewing.select("attachment:w")
        viewing.playing = true
        viewing.positionMs = 7_500L
        val restored = ViewingMedia.Saver.restore(assertNotNull(save(viewing)))
        assertNotNull(restored)
        assertEquals("m1", restored.messageId)
        assertEquals("attachment:v", restored.openedKey)
        assertEquals("attachment:w", restored.currentKey)
        assertTrue(restored.playing)
        assertEquals(7_500L, restored.positionMs)
    }

    @Test
    fun estadoSalvoIlegivelNaoReabreVisualizadorNenhum() {
        assertNull(ViewingMedia.Saver.restore("não é uma lista"))
        assertNull(ViewingMedia.Saver.restore(emptyList<Any>()))
        assertNull(ViewingMedia.Saver.restore(listOf("m1", "attachment:a", "attachment:a", false)))
        assertNull(ViewingMedia.Saver.restore(listOf("m1", "attachment:a", "attachment:a", "não", 0L)))
        assertNull(ViewingMedia.Saver.restore(listOf("m1", "attachment:a", "attachment:a", false, "0")))
        assertNull(ViewingMedia.Saver.restore(listOf(1, "attachment:a", "attachment:a", false, 0L)))
    }
}
