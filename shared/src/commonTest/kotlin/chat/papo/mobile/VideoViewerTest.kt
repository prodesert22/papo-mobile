package chat.papo.mobile

import androidx.compose.runtime.saveable.SaverScope
import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.Message
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.ui.media.AttachmentDownload
import chat.papo.mobile.ui.media.FailureActions
import chat.papo.mobile.ui.media.PlayingVideo
import chat.papo.mobile.ui.media.failureActions
import chat.papo.mobile.ui.media.findOpenVideo
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
import kotlin.test.assertNull
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

    private fun message(id: String, vararg attachments: AttachmentDto) = Message(
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
        attachments = attachments.toList(),
        embeds = emptyList(),
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

    // failureActions

    @Test
    fun semAcessoSemArquivoOuMudadoNaoHaOQueRepetirNemBaixar() {
        for (failure in listOf(PlaybackFailure.Unauthorized, PlaybackFailure.NotFound, PlaybackFailure.Gone)) {
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
