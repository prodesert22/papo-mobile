package chat.papo.mobile

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.platform.UriHandler
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.Message
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedMediaDto
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.resources.Res
import chat.papo.mobile.resources.video_embed_failed_not_found
import chat.papo.mobile.resources.video_embed_failed_unavailable
import chat.papo.mobile.resources.video_failed_forbidden
import chat.papo.mobile.resources.video_failed_gone
import chat.papo.mobile.resources.video_failed_network
import chat.papo.mobile.resources.video_failed_not_found
import chat.papo.mobile.resources.video_failed_rate_limited
import chat.papo.mobile.resources.video_failed_session_ended
import chat.papo.mobile.resources.video_failed_unavailable
import chat.papo.mobile.resources.video_failed_unsupported
import chat.papo.mobile.ui.media.AttachmentDownload
import chat.papo.mobile.ui.media.FailureActions
import chat.papo.mobile.ui.media.OpenVideo
import chat.papo.mobile.ui.media.PlayingVideo
import chat.papo.mobile.ui.media.failureActions
import chat.papo.mobile.ui.media.failureMessage
import chat.papo.mobile.ui.media.findOpenVideo
import chat.papo.mobile.ui.media.openInBrowser
import chat.papo.mobile.ui.media.webLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** O que o visualizador de vídeo decide sem desenhar nada: o estado salvo, achar a mídia, as ações da falha e o Baixar. */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoViewerTest {
    private val server = ServerUrl("https://papo.test")

    private fun attachment(id: String, mimeType: String = "video/mp4", moderationStatus: String? = null) = AttachmentDto(
        id = id,
        mimeType = mimeType,
        fileName = "$id.mp4",
        sizeBytes = 1_000,
        moderationStatus = moderationStatus,
    )

    private fun message(id: String, vararg attachments: AttachmentDto) = messageWith(id, attachments.toList(), emptyList())

    private fun embedMessage(id: String, vararg embeds: EmbedDto) = messageWith(id, emptyList(), embeds.toList())

    private fun embed(
        id: String,
        url: String? = "https://videos.example.com/$id.mp4",
        video: EmbedMediaDto? = EmbedMediaDto("https://videos.example.com/$id.mp4", "video/mp4", 640, 360),
    ) = EmbedDto(id = id, url = url, title = "Título $id", video = video)

    private fun messageWith(id: String, attachments: List<AttachmentDto>, embeds: List<EmbedDto>) = Message(
        id = id,
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

    // PlayingVideo.Saver

    private val saverScope = SaverScope { true }

    private fun save(video: PlayingVideo?): Any? = with(PlayingVideo.Saver) { saverScope.save(video) }

    @Test
    fun semVideoAbertoNadaSeSalva() {
        assertNull(save(null))
    }

    @Test
    fun aChaveEOPontoVoltamDepoisDeGirar() {
        val restored = PlayingVideo.Saver.restore(assertNotNull(save(PlayingVideo("attachment:a1", 42_000))))
        assertNotNull(restored)
        assertEquals("attachment:a1", restored.key)
        assertEquals(42_000, restored.positionMs)
    }

    @Test
    fun salvaOPontoDaHoraDeSalvarEnaoODaCriacao() {
        val video = PlayingVideo("attachment:a1")
        assertEquals(0L, video.positionMs)
        video.positionMs = 7_500
        val restored = PlayingVideo.Saver.restore(assertNotNull(save(video)))
        assertEquals(7_500, restored?.positionMs)
    }

    @Test
    fun estadoSalvoIlegivelNaoReabreVideoNenhum() {
        assertNull(PlayingVideo.Saver.restore("não é uma lista"))
        assertNull(PlayingVideo.Saver.restore(emptyList<Any>()))
        assertNull(PlayingVideo.Saver.restore(listOf("attachment:a1")))
        assertNull(PlayingVideo.Saver.restore(listOf(1, 2L)))
        assertNull(PlayingVideo.Saver.restore(listOf("attachment:a1", "10")))
    }

    // findOpenVideo

    @Test
    fun oAnexoAchadoLevaAMensagemDeOndeSaiuEOEmbedNao() {
        val found = findOpenVideo(server, listOf(message("m1", attachment("a1")), message("m2", attachment("a2"))), "attachment:a2")
        assertEquals("m2", found?.messageId)
        // Só o anexo pode ser gravado como bloqueado: o embed não tem anexo nem mensagem de onde o 410 o tire.
        assertNull(assertNotNull(findOpenVideo(server, listOf(embedMessage("m3", embed("e1"))), "embed:e1")).messageId)
    }

    @Test
    fun achaOAnexoPelaChaveEDevolveAMidiaDoServidorEOAnexoParaBaixar() {
        val wanted = attachment("a2")
        val found = findOpenVideo(server, listOf(message("m1", attachment("a1")), message("m2", wanted)), "attachment:a2")
        assertNotNull(found)
        assertEquals("attachment:a2", found.media.key)
        assertEquals("https://papo.test/attachments/a2", found.media.url)
        assertSame(wanted, found.attachment)
    }

    @Test
    fun achaOAnexoEntreVariosNaMesmaMensagem() {
        val found = findOpenVideo(server, listOf(message("m1", attachment("img", "image/png"), attachment("a1"), attachment("a2"))), "attachment:a2")
        assertEquals("a2", found?.attachment?.id)
    }

    @Test
    fun semMensagensOuSemAChaveNaoHaVideo() {
        assertNull(findOpenVideo(server, emptyList(), "attachment:a1"))
        assertNull(findOpenVideo(server, listOf(message("m1", attachment("a1"))), "attachment:a9"))
        assertNull(findOpenVideo(server, listOf(message("m1")), "attachment:a1"))
    }

    @Test
    fun anexoQueOPlayerNaoAbreNaoVira() {
        assertNull(findOpenVideo(server, listOf(message("m1", attachment("a1", moderationStatus = "blocked"))), "attachment:a1"))
        assertNull(findOpenVideo(server, listOf(message("m1", attachment("a1", mimeType = "image/png"))), "attachment:a1"))
    }

    @Test
    fun chaveDeOutroTipoDeMidiaNaoCasaComAnexo() {
        assertNull(findOpenVideo(server, listOf(message("m1", attachment("a1"))), "embed:a1"))
    }

    @Test
    fun achaOEmbedPelaChaveEDevolveORelayDaContaSemArquivoParaBaixar() {
        val found = findOpenVideo(server, listOf(embedMessage("m1", embed("e1"), embed("e2"))), "embed:e2")
        assertNotNull(found)
        assertEquals("embed:e2", found.media.key)
        assertEquals("https://papo.test/embeds/e2/video", found.media.url)
        assertEquals("Título e2", found.media.title)
        assertNull(found.attachment)
        assertTrue(found.isEmbed)
        assertEquals("https://videos.example.com/e2.mp4", found.pageUrl)
    }

    @Test
    fun achaOEmbedDeUmaMensagemDepoisDeOutrasComAnexoEmbedSemVideoEOutroEmbed() {
        val messages = listOf(
            message("m1", attachment("a1")),
            embedMessage("m2", embed("sem-video", video = null)),
            embedMessage("m3", embed("e1")),
        )
        assertEquals("embed:e1", findOpenVideo(server, messages, "embed:e1")?.media?.key)
    }

    @Test
    fun anexoEEmbedNaMesmaMensagemSaoAchadosCadaUmPelaSuaChave() {
        val both = messageWith("m1", listOf(attachment("a1")), listOf(embed("e1")))
        val attachment = findOpenVideo(server, listOf(both), "attachment:a1")
        val embed = findOpenVideo(server, listOf(both), "embed:e1")
        assertEquals("a1", attachment?.attachment?.id)
        assertEquals(false, attachment?.isEmbed)
        assertNull(attachment?.pageUrl)
        assertNull(embed?.attachment)
    }

    @Test
    fun embedQueORelayNaoServeNaoVira() {
        val youtube = EmbedMediaDto("https://www.youtube.com/embed/dQw4w9WgXcQ", null, null, null)
        val http = EmbedMediaDto("http://videos.example.com/e1.mp4", "video/mp4", null, null)
        val unknownType = EmbedMediaDto("https://videos.example.com/e1.mov", "video/quicktime", null, null)
        for (video in listOf(null, youtube, http, unknownType)) {
            assertNull(findOpenVideo(server, listOf(embedMessage("m1", embed("e1", video = video))), "embed:e1"), "$video")
        }
    }

    @Test
    fun chaveDeEmbedNaoCasaComAnexoDeMesmoIdEViceVersa() {
        assertNull(findOpenVideo(server, listOf(embedMessage("m1", embed("x1"))), "attachment:x1"))
        assertNull(findOpenVideo(server, listOf(message("m1", attachment("x1"))), "embed:x1"))
        assertNull(findOpenVideo(server, listOf(embedMessage("m1", embed("e1"))), "embed:e9"))
    }

    @Test
    fun embedTrocadoPorUmaListaVaziaNaoAbreMaisNada() {
        assertNull(findOpenVideo(server, listOf(embedMessage("m1")), "embed:e1"))
    }

    @Test
    fun paginaDoEmbedSoVaiParaONavegadorSendoHttpOuHttps() {
        for (url in listOf("https://videos.example.com/p", "http://videos.example.com/p")) {
            assertEquals(url, findOpenVideo(server, listOf(embedMessage("m1", embed("e1", url = url))), "embed:e1")?.pageUrl, url)
        }
        for (url in listOf(null, "", "javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/x", "ftp://x.test/v", "//x.test/v", "/v")) {
            val found = findOpenVideo(server, listOf(embedMessage("m1", embed("e1", url = url))), "embed:e1")
            assertNotNull(found, "$url")
            assertNull(found.pageUrl, "$url")
        }
    }

    // webLink

    @Test
    fun esquemaSaiEmMinusculasEORestoFicaComoVeio() {
        assertEquals("https://Videos.Example.com/P?Q=1", webLink("Https://Videos.Example.com/P?Q=1"))
        assertEquals("http://a.test/x://y", webLink("HTTP://a.test/x://y"))
    }

    @Test
    fun soHttpEHttpsComHostSaoLinksDaWeb() {
        for (url in listOf("https://a.test", "http://a.test/p?q=1#f", "https://A.test/P", "https://a.test:8443/p", "https://u@a.test/")) {
            assertEquals(url, webLink(url), url)
        }
        for (url in listOf(
            null, "", " ", "https://", "https:///p", "http://?x", "https://a.test/p q", "https://a.test/\n", " https://a.test", "https:a.test",
            "javascript:alert(1)", "data:text/html,x", "content://x/y", "mailto:a@a.test", "https//a.test",
        )) {
            assertNull(webLink(url), "$url")
        }
    }

    // openInBrowser

    private class RecordingUriHandler(private val failure: Throwable? = null) : UriHandler {
        val opened = mutableListOf<String>()

        override fun openUri(uri: String) {
            opened += uri
            failure?.let { throw it }
        }
    }

    @Test
    fun abrirNoNavegadorEntregaOEnderecoAoSistema() {
        val handler = RecordingUriHandler()
        openInBrowser(handler, "https://videos.example.com/p")
        assertEquals(listOf("https://videos.example.com/p"), handler.opened)
    }

    @Test
    fun semAplicativoParaAbrirAPaginaOVisualizadorNaoCai() {
        val handler = RecordingUriHandler(IllegalArgumentException("Can't open"))
        openInBrowser(handler, "https://videos.example.com/p")
        assertEquals(1, handler.opened.size)
    }

    @Test
    fun outroErroAoAbrirNaoESilenciado() {
        assertFailsWith<IllegalStateException> { openInBrowser(RecordingUriHandler(IllegalStateException("bug")), "https://videos.example.com/p") }
        assertFailsWith<CancellationException> { openInBrowser(RecordingUriHandler(CancellationException("fim")), "https://videos.example.com/p") }
    }

    // failureActions

    @Test
    fun semSessaoSemAcessoSemArquivoOuMudadoNaoHaOQueRepetirNemBaixar() {
        for (failure in listOf(PlaybackFailure.SessionEnded, PlaybackFailure.Forbidden, PlaybackFailure.NotFound, PlaybackFailure.Gone)) {
            assertEquals(FailureActions(retry = false, download = false), failureActions(failure, canDownload = true), "$failure")
            assertEquals(FailureActions(retry = false, download = false), failureActions(failure, canDownload = false), "$failure")
        }
    }

    @Test
    fun formatoSemSuporteNaoRepeteMasOferecePorBaixarSeHaArquivo() {
        assertEquals(FailureActions(retry = false, download = true), failureActions(PlaybackFailure.Unsupported, canDownload = true))
        assertEquals(FailureActions(retry = false, download = false), failureActions(PlaybackFailure.Unsupported, canDownload = false))
    }

    @Test
    fun falhaQuePodePassarOferecePorRepetirEBaixarSeHaArquivo() {
        for (failure in listOf(PlaybackFailure.RateLimited, PlaybackFailure.Unavailable, PlaybackFailure.Network)) {
            assertEquals(FailureActions(retry = true, download = true), failureActions(failure, canDownload = true), "$failure")
            assertEquals(FailureActions(retry = true, download = false), failureActions(failure, canDownload = false), "$failure")
        }
    }

    @Test
    fun videoDeEmbedSemArquivoOferecePelaPaginaOndeRepetirNaoAdianta() {
        for (failure in listOf(PlaybackFailure.NotFound, PlaybackFailure.Gone, PlaybackFailure.Unsupported)) {
            assertEquals(
                FailureActions(retry = false, download = false, browser = true),
                failureActions(failure, canDownload = false, canOpenInBrowser = true),
                "$failure",
            )
            assertEquals(
                FailureActions(retry = false, download = false, browser = false),
                failureActions(failure, canDownload = false, canOpenInBrowser = false),
                "$failure",
            )
        }
    }

    @Test
    fun falhaDoRelayQuePodePassarOfereceSoRepetirSemPagina() {
        for (failure in listOf(PlaybackFailure.RateLimited, PlaybackFailure.Unavailable, PlaybackFailure.Network)) {
            assertEquals(
                FailureActions(retry = true, download = false, browser = false),
                failureActions(failure, canDownload = false, canOpenInBrowser = true),
                "$failure",
            )
        }
    }

    @Test
    fun semSessaoOuSemAcessoNemAPaginaResolve() {
        for (failure in listOf(PlaybackFailure.SessionEnded, PlaybackFailure.Forbidden)) {
            assertEquals(
                FailureActions(retry = false, download = false, browser = false),
                failureActions(failure, canDownload = true, canOpenInBrowser = true),
                "$failure",
            )
        }
    }

    @Test
    fun formatoSemSuporteJuntaBaixarEPaginaQuandoAsDuasExistem() {
        assertEquals(
            FailureActions(retry = false, download = true, browser = true),
            failureActions(PlaybackFailure.Unsupported, canDownload = true, canOpenInBrowser = true),
        )
    }

    // failureMessage

    @Test
    fun embedDiz404ComoVideoIndisponivelE5xxComoFalhaAoBuscarNaOrigem() {
        assertSame(Res.string.video_embed_failed_not_found, failureMessage(PlaybackFailure.NotFound, embed = true))
        assertSame(Res.string.video_embed_failed_unavailable, failureMessage(PlaybackFailure.Unavailable, embed = true))
    }

    @Test
    fun anexoMantemOsTextosDeSempre() {
        assertSame(Res.string.video_failed_not_found, failureMessage(PlaybackFailure.NotFound, embed = false))
        assertSame(Res.string.video_failed_unavailable, failureMessage(PlaybackFailure.Unavailable, embed = false))
    }

    @Test
    fun sessaoEncerradaETextoProprioDiferenteDeSemAcesso() {
        assertNotSame(failureMessage(PlaybackFailure.SessionEnded, embed = false), failureMessage(PlaybackFailure.Forbidden, embed = false))
        assertNotSame(failureMessage(PlaybackFailure.SessionEnded, embed = true), failureMessage(PlaybackFailure.Forbidden, embed = true))
    }

    @Test
    fun asDemaisFalhasTemOMesmoTextoNoAnexoENoEmbed() {
        val expected = mapOf(
            PlaybackFailure.SessionEnded to Res.string.video_failed_session_ended,
            PlaybackFailure.Forbidden to Res.string.video_failed_forbidden,
            PlaybackFailure.Gone to Res.string.video_failed_gone,
            PlaybackFailure.RateLimited to Res.string.video_failed_rate_limited,
            PlaybackFailure.Unsupported to Res.string.video_failed_unsupported,
            PlaybackFailure.Network to Res.string.video_failed_network,
        )
        for ((failure, text) in expected) {
            assertSame(text, failureMessage(failure, embed = false), "$failure")
            assertSame(text, failureMessage(failure, embed = true), "$failure")
        }
    }

    // OpenVideo

    @Test
    fun soOAnexoTemArquivoParaBaixar() {
        val media = chat.papo.mobile.media.PlayableMedia("attachment:a1", "https://papo.test/attachments/a1", "video/mp4", "a1.mp4", null, null)
        assertFalse(OpenVideo(media, attachment("a1")).isEmbed)
        assertTrue(OpenVideo(media, attachment = null).isEmbed)
    }

    // AttachmentDownload

    @Test
    fun baixarGravaEVoltaAoRepouso() = runTest {
        val download = AttachmentDownload(this) { true }
        download.start()
        assertTrue(download.downloading)
        runCurrent()
        assertFalse(download.downloading)
        assertFalse(download.failed)
    }

    @Test
    fun arquivoQueNaoGravouMarcaAFalha() = runTest {
        val download = AttachmentDownload(this) { false }
        download.start()
        runCurrent()
        assertFalse(download.downloading)
        assertTrue(download.failed)
    }

    @Test
    fun erroNoMeioDoDownloadMarcaAFalhaEnaoDerrubaNada() = runTest {
        val download = AttachmentDownload(this) { throw Error("sem memória") }
        download.start()
        runCurrent()
        assertFalse(download.downloading)
        assertTrue(download.failed)
    }

    @Test
    fun cancelarDevolveOEstadoSemContarComoFalha() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val never = CompletableDeferred<Boolean>()
        val download = AttachmentDownload(scope) { never.await() }
        download.start()
        runCurrent()
        assertTrue(download.downloading)
        scope.cancel()
        runCurrent()
        assertFalse(download.downloading)
        assertFalse(download.failed)
    }

    @Test
    fun cancelamentoLevantadoPeloProprioDownloadTambemNaoViraFalha() = runTest {
        val download = AttachmentDownload(this) { throw CancellationException("tela saiu") }
        download.start()
        runCurrent()
        assertFalse(download.downloading)
        assertFalse(download.failed)
    }

    @Test
    fun tocarDeNovoEnquantoBaixaNaoFazOutroPedido() = runTest {
        var calls = 0
        val result = CompletableDeferred<Boolean>()
        val download = AttachmentDownload(this) {
            calls++
            result.await()
        }
        download.start()
        runCurrent()
        download.start()
        runCurrent()
        assertEquals(1, calls)
        result.complete(true)
        runCurrent()
        assertFalse(download.downloading)
    }

    @Test
    fun novaTentativaDepoisDeFalharLimpaAFalhaEnquantoBaixa() = runTest {
        val results = ArrayDeque(listOf(false, true))
        val gate = CompletableDeferred<Unit>()
        val download = AttachmentDownload(this) {
            if (results.size == 1) gate.await()
            results.removeFirst()
        }
        download.start()
        runCurrent()
        assertTrue(download.failed)
        download.start()
        assertTrue(download.downloading)
        assertFalse(download.failed)
        gate.complete(Unit)
        runCurrent()
        assertFalse(download.failed)
        assertFalse(download.downloading)
    }
}
