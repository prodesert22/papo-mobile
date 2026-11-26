package chat.papo.mobile

import androidx.compose.runtime.saveable.SaverScope
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.Message
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedMediaDto
import chat.papo.mobile.media.playableAttachment
import chat.papo.mobile.media.relayVideo
import chat.papo.mobile.ui.media.ViewerItem
import chat.papo.mobile.ui.media.ViewingMedia
import chat.papo.mobile.ui.media.viewerEmbedKey
import chat.papo.mobile.ui.media.viewerItems
import chat.papo.mobile.ui.media.viewerKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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

    private fun message(vararg attachments: AttachmentDto) = messageOf(attachments.toList())

    private fun messageOf(attachments: List<AttachmentDto> = emptyList(), embeds: List<EmbedDto> = emptyList()) = Message(
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
        attachments = attachments,
        embeds = embeds,
        reactions = emptyList(),
        userReactions = emptyList(),
    )

    private fun picture(mime: String = "image/png") = EmbedMediaDto(mimeType = mime, width = 800, height = 600)

    /** Um embed com a imagem na `thumbnail`, como o de um link comum. */
    private fun embed(id: String, title: String? = "Título $id", thumbnail: EmbedMediaDto? = picture()) =
        EmbedDto(id = id, title = title, thumbnail = thumbnail)

    private fun gifEmbed(id: String) = EmbedDto(id = id, fetchMethod = "gif", provider = "Tenor", thumbnail = picture("image/gif"))

    private fun relayEmbed(id: String) = embed(id).copy(video = EmbedMediaDto(url = "https://videos.example.com/$id.mp4", mimeType = "video/mp4"))

    private fun youTubeEmbed(id: String) = embed(id).copy(video = EmbedMediaDto(url = "https://www.youtube.com/embed/aB3dE5gH7jK"))

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

    // viewerItems com embeds

    @Test
    fun asImagensDosEmbedsVemDepoisDosAnexosNaOrdemDaMensagem() {
        val message = messageOf(listOf(image("a"), video("b")), listOf(embed("e1"), embed("e2")))
        val shown = viewerItems(server, message, viewerEmbedKey("e2"))
        assertEquals(listOf("attachment:a", "attachment:b", "embed-image:e1", "embed-image:e2"), shown.items.map { it.key })
        assertEquals(3, shown.start)
        // Tocar num anexo ou no primeiro embed abre na página certa, e o resto da fileira é o mesmo.
        assertEquals(0, viewerItems(server, message, viewerKey("a")).start)
        assertEquals(2, viewerItems(server, message, viewerEmbedKey("e1")).start)
    }

    @Test
    fun mensagemSoComEmbedsTemUmaPaginaPorEmbedComImagem() {
        val message = messageOf(embeds = listOf(embed("e1"), embed("e2", thumbnail = null), embed("e3")))
        val shown = viewerItems(server, message, viewerEmbedKey("e3"))
        assertEquals(listOf("embed-image:e1", "embed-image:e3"), shown.items.map { it.key })
        assertEquals(1, shown.start)
        assertEquals(listOf("e1", "e3"), shown.items.map { assertIs<ViewerItem.EmbedImage>(it).embed.id })
    }

    @Test
    fun embedSemImagemFicaDeFora() {
        val message = messageOf(listOf(image("a")), listOf(embed("e1", thumbnail = null), EmbedDto(id = "e2", title = "só texto")))
        assertEquals(listOf("attachment:a"), keysOf(message, viewerKey("a")))
        assertTrue(viewerItems(server, messageOf(embeds = listOf(embed("e1", thumbnail = null))), viewerEmbedKey("e1")).items.isEmpty())
    }

    @Test
    fun embedSoComImageEntraPorqueOServidorAServeNoLugarDaThumbnail() {
        val custom = EmbedDto(id = "e1", fetchMethod = "manual", image = picture())
        assertEquals(listOf("embed-image:e1"), keysOf(messageOf(embeds = listOf(custom)), viewerEmbedKey("e1")))
    }

    @Test
    fun embedComThumbnailEImageTemUmaPaginaSoPorqueOServidorServeUmaImagemPorEmbed() {
        val both = EmbedDto(id = "e1", fetchMethod = "manual", thumbnail = picture(), image = picture("image/jpeg"))
        assertEquals(listOf("embed-image:e1"), keysOf(messageOf(embeds = listOf(both)), viewerEmbedKey("e1")))
    }

    @Test
    fun embedComVideoDoRelayOuDoYouTubeFicaDeForaPorqueOCartaoTemOGestoDoVideo() {
        assertNotNull(relayVideo(server, relayEmbed("v1")))
        val message = messageOf(embeds = listOf(relayEmbed("v1"), embed("e1"), youTubeEmbed("y1")))
        assertEquals(listOf("embed-image:e1"), keysOf(message, viewerEmbedKey("e1")))
        // Tocado ou não, o vídeo não vira página: abre na primeira.
        assertEquals(0, viewerItems(server, message, viewerEmbedKey("v1")).start)
    }

    @Test
    fun videoDeEmbedQueNaoTocaNoCartaoDeixaAImagemSerPaginaComum() {
        // `http://` e MIME de arquivo que o relay não serve: o cartão só mostra a imagem.
        val plain = embed("e1").copy(video = EmbedMediaDto(url = "http://videos.example.com/a.mp4", mimeType = "video/mp4"))
        val odd = embed("e2").copy(video = EmbedMediaDto(url = "https://videos.example.com/a.avi", mimeType = "video/x-msvideo"))
        assertEquals(listOf("embed-image:e1", "embed-image:e2"), keysOf(messageOf(embeds = listOf(plain, odd)), viewerEmbedKey("e1")))
    }

    @Test
    fun gifDeEmbedEntraComoImagemMesmoSeOServidorMandarUmVideo() {
        val withVideo = gifEmbed("g2").copy(video = EmbedMediaDto(url = "https://videos.example.com/g.mp4", mimeType = "video/mp4"))
        val message = messageOf(embeds = listOf(gifEmbed("g1"), withVideo))
        assertEquals(listOf("embed-image:g1", "embed-image:g2"), keysOf(message, viewerEmbedKey("g1")))
    }

    @Test
    fun embedRepetidoNaMensagemViraUmaPaginaSo() {
        val message = messageOf(embeds = listOf(embed("e1"), embed("e2"), embed("e1")))
        assertEquals(listOf("embed-image:e1", "embed-image:e2"), keysOf(message, viewerEmbedKey("e2")))
    }

    @Test
    fun embedQueSaiuDaMensagemAbreNaPrimeiraPagina() {
        val message = messageOf(listOf(image("a")), listOf(embed("e1")))
        val shown = viewerItems(server, message, viewerEmbedKey("saiu"))
        assertEquals(0, shown.start)
        assertEquals(listOf("attachment:a", "embed-image:e1"), shown.items.map { it.key })
        // Sem nenhuma página sobrando o visualizador se fecha: a lista vazia é o sinal.
        assertTrue(viewerItems(server, messageOf(embeds = listOf(embed("e1", thumbnail = null))), viewerEmbedKey("e1")).items.isEmpty())
    }

    @Test
    fun tocarNoEmbedNaoRevelaOSensivelEBloqueadoContinuaFora() {
        val message = messageOf(
            listOf(image("a"), image("s", status = "sensitive"), image("b", status = "blocked"), video("t", status = "sensitive")),
            listOf(embed("e1")),
        )
        // Aberto num embed, só o que não é sensível nem bloqueado aparece.
        val fromEmbed = viewerItems(server, message, viewerEmbedKey("e1"))
        assertEquals(listOf("attachment:a", "embed-image:e1"), fromEmbed.items.map { it.key })
        assertEquals(1, fromEmbed.start)
        // O sensível tocado entra, como antes, e o embed segue depois dele.
        val fromSensitive = viewerItems(server, message, viewerKey("s"))
        assertEquals(listOf("attachment:a", "attachment:s", "embed-image:e1"), fromSensitive.items.map { it.key })
        assertEquals(1, fromSensitive.start)
    }

    @Test
    fun aChaveDaImagemDoEmbedNaoSeConfundeComAChaveDoVideoDele() {
        assertEquals("embed-image:e1", viewerEmbedKey("e1"))
        assertEquals("embed:e1", relayVideo(server, relayEmbed("e1"))?.key)
        assertNotEquals(viewerEmbedKey("e1"), relayVideo(server, relayEmbed("e1"))?.key)
        assertNotEquals(viewerEmbedKey("a"), viewerKey("a"))
    }

    @Test
    fun oTituloDaPaginaVemDoNomeDoArquivoOuDoTituloDoEmbed() {
        val message = messageOf(
            listOf(image("a")),
            listOf(
                embed("e1", title = "Build 42 passou"),
                embed("e2", title = "  ").copy(siteName = "exemplo.com", provider = "Outro"),
                embed("e3", title = null).copy(provider = "Tenor"),
                embed("e4", title = null),
            ),
        )
        val titles = viewerItems(server, message, viewerKey("a")).items.map { it.title }
        assertEquals(listOf("a.jpg", "Build 42 passou", "exemplo.com", "Tenor", ""), titles)
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
    fun abrirNaImagemDeUmEmbedGuardaAChaveDelaComoAPrimeiraEComoAAtualSemTocarNada() {
        val viewing = ViewingMedia.embedImage("m1", "e1")
        assertEquals("m1", viewing.messageId)
        assertEquals("embed-image:e1", viewing.openedKey)
        assertEquals("embed-image:e1", viewing.currentKey)
        assertFalse(viewing.playing)
        assertEquals(0L, viewing.positionMs)
    }

    @Test
    fun aImagemDoEmbedVoltaDepoisDeGirarEAbreNaPaginaCerta() {
        val viewing = ViewingMedia.embedImage("m1", "e1")
        viewing.select("embed-image:e2")
        val restored = assertNotNull(ViewingMedia.Saver.restore(assertNotNull(save(viewing))))
        assertEquals("embed-image:e1", restored.openedKey)
        assertEquals("embed-image:e2", restored.currentKey)
        val message = messageOf(listOf(image("a")), listOf(embed("e1"), embed("e2")))
        assertEquals(3, viewerItems(server, message, restored.openedKey).items.size)
        assertEquals(2, viewerItems(server, message, restored.currentKey).start)
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
        assertNull(ViewingMedia.Saver.restore(listOf("m1", 2, "attachment:a", false, 0L)))
        assertNull(ViewingMedia.Saver.restore(listOf("m1", "attachment:a", 3, false, 0L)))
    }
}
