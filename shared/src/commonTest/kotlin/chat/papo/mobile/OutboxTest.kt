package chat.papo.mobile

import chat.papo.mobile.chat.Outbox
import chat.papo.mobile.chat.PendingMessage
import chat.papo.mobile.chat.PendingStatus
import chat.papo.mobile.chat.SendFailure
import chat.papo.mobile.chat.classifySendFailure
import chat.papo.mobile.chat.matchPending
import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.FileUnavailableException
import chat.papo.mobile.data.api.OutgoingFile
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val NOW = Instant.parse("2026-03-01T12:00:00Z")

private fun api(status: Int, title: String? = null, detail: String? = null) = ApiException(status, title, detail, null)

private fun file(name: String = "a.bin") = bytesFile(name, "application/octet-stream", byteArrayOf(1, 2, 3))

class SendFailureTest {
    @Test
    fun limiteDeTaxaNaoPodeSerRepetidoSozinho() {
        assertEquals(PendingStatus.Failed(SendFailure.RateLimited), classifySendFailure(api(429, "Limite", "calma")))
    }

    @Test
    fun recusasDoServidorViramRejectedComOTextoDele() {
        for (status in listOf(400, 403, 404, 415, 422, 409)) {
            assertEquals(PendingStatus.Failed(SendFailure.Rejected, "motivo"), classifySendFailure(api(status, "Título", "motivo")), "status $status")
        }
    }

    @Test
    fun corpoGrandeDemaisEUmMotivoProprioSemOTextoDoServidor() {
        // O servidor responde 413 sem ler o corpo: nada foi criado, e o motivo é o mesmo qualquer que seja o texto dele.
        assertEquals(PendingStatus.Failed(SendFailure.TooLarge), classifySendFailure(api(413, "Payload muito grande", "corpo da requisição excede o tamanho máximo permitido")))
        assertEquals(PendingStatus.Failed(SendFailure.TooLarge), classifySendFailure(api(413), bodyDelivered = false))
    }

    @Test
    fun arquivoQueSumiuNuncaChegouAoServidorQualquerQueSejaOPontoDoEnvio() {
        val lost = FileUnavailableException("foto.jpg", IOException("sumiu"))
        val expected = PendingStatus.Failed(SendFailure.FileUnavailable, "foto.jpg")
        assertEquals(expected, classifySendFailure(lost))
        assertEquals(expected, classifySendFailure(lost, bodyDelivered = true))
        assertEquals(expected, classifySendFailure(lost, bodyDelivered = false))
    }

    @Test
    fun semDetalheORejectedUsaOTituloEDepoisNada() {
        assertEquals(PendingStatus.Failed(SendFailure.Rejected, "Título"), classifySendFailure(api(400, "Título", null)))
        assertEquals(PendingStatus.Failed(SendFailure.Rejected, null), classifySendFailure(api(404, null, null)))
    }

    @Test
    fun sessaoQueNaoValeMaisEUnauthorized() {
        assertEquals(PendingStatus.Failed(SendFailure.Unauthorized), classifySendFailure(api(401)))
    }

    @Test
    fun erroDoServidorPodeTerCriadoAMensagem() {
        for (status in listOf(500, 502, 503, 504)) assertEquals(PendingStatus.Uncertain, classifySendFailure(api(status)), "status $status")
        // Um código que nem é de erro HTTP, vindo de um intermediário, também não prova nada.
        assertEquals(PendingStatus.Uncertain, classifySendFailure(api(302)))
    }

    @Test
    fun falhaAoConectarNuncaChegouAoServidor() {
        val connect = ConnectTimeoutException("sem rota", null)
        assertEquals(PendingStatus.Failed(SendFailure.NotSent), classifySendFailure(connect))
        assertEquals(PendingStatus.Failed(SendFailure.NotSent), classifySendFailure(connect, bodyDelivered = false))
    }

    @Test
    fun erroDeRedeDependeDeTerSaidoOCorpoInteiro() {
        val cut = IOException("reset")
        assertEquals(PendingStatus.Uncertain, classifySendFailure(cut, bodyDelivered = true))
        assertEquals(PendingStatus.Uncertain, classifySendFailure(cut)) // sem saber, é o caso conservador
        assertEquals(PendingStatus.Failed(SendFailure.NotSent), classifySendFailure(cut, bodyDelivered = false))
    }

    @Test
    fun tempoEsgotadoSegueAMesmaRegraDeErroDeRede() {
        val timeout = HttpRequestTimeoutException("https://papo.test/messages", 1000L, null)
        assertEquals(PendingStatus.Uncertain, classifySendFailure(timeout, bodyDelivered = true))
        assertEquals(PendingStatus.Failed(SendFailure.NotSent), classifySendFailure(timeout, bodyDelivered = false))
    }

    @Test
    fun respostaIlegivelDepoisDoEnvioFicaIncerta() {
        assertEquals(PendingStatus.Uncertain, classifySendFailure(SerializationException("json"), bodyDelivered = true))
    }

    @Test
    fun cancelamentoNuncaViraFalha() {
        assertFailsWith<CancellationException> { classifySendFailure(CancellationException("parou")) }
        assertFailsWith<CancellationException> { classifySendFailure(CancellationException("parou"), bodyDelivered = false) }
    }
}

class MatchPendingTest {
    private fun pending(
        id: String,
        status: PendingStatus,
        content: String = "oi",
        channel: String = "c1",
        replyTo: String? = null,
        files: List<OutgoingFile> = emptyList(),
        at: Instant = NOW,
    ) = PendingMessage(id, channel, content, replyTo, files, at, status)

    private val sending = PendingStatus.Sending

    @Test
    fun casaComAMaisAntigaQuandoHaDuasIguais() {
        val list = listOf(pending("a", sending), pending("b", sending))
        assertEquals("a", matchPending(list, "c1", "oi", null, 0)?.localId)
    }

    @Test
    fun incertaTambemCasaMasNaOrdemDaFila() {
        val list = listOf(pending("a", PendingStatus.Uncertain), pending("b", sending))
        assertEquals("a", matchPending(list, "c1", "oi", null, 0)?.localId)
        assertEquals("b", matchPending(listOf(pending("a", PendingStatus.Queued), pending("b", sending)), "c1", "oi", null, 0)?.localId)
    }

    @Test
    fun soQuemPodeJaExistirNoServidorCasa() {
        val list = listOf(
            pending("a", PendingStatus.Queued),
            pending("b", PendingStatus.Failed(SendFailure.Rejected)),
            pending("c", PendingStatus.Failed(SendFailure.NotSent)),
        )
        assertNull(matchPending(list, "c1", "oi", null, 0))
    }

    @Test
    fun oConteudoCompararAparado() {
        val list = listOf(pending("a", sending, content = "oi"))
        assertEquals("a", matchPending(list, "c1", "  oi \n", null, 0)?.localId)
        assertNull(matchPending(list, "c1", "oi!", null, 0))
        // Mensagem só com anexo chega sem texto.
        val onlyFile = listOf(pending("f", sending, content = "", files = listOf(file())))
        assertEquals("f", matchPending(onlyFile, "c1", null, null, 1)?.localId)
        assertEquals("f", matchPending(onlyFile, "c1", "   ", null, 1)?.localId)
    }

    @Test
    fun canalRespostaEAnexosPrecisamSerOsMesmos() {
        val list = listOf(pending("a", sending, replyTo = "m1", files = listOf(file())))
        assertEquals("a", matchPending(list, "c1", "oi", "m1", 1)?.localId)
        assertNull(matchPending(list, "c2", "oi", "m1", 1))
        assertNull(matchPending(list, "c1", "oi", null, 1))
        assertNull(matchPending(list, "c1", "oi", "m2", 1))
        assertNull(matchPending(list, "c1", "oi", "m1", 0))
        assertNull(matchPending(list, "c1", "oi", "m1", 2))
    }

    @Test
    fun paginaDeHistoricoIgnoraMensagemMaisAntigaQueOEnvio() {
        val list = listOf(pending("a", PendingStatus.Uncertain, at = NOW))
        assertEquals("a", matchPending(list, "c1", "oi", null, 0, createdAt = NOW + 1.minutes)?.localId)
        // Os relógios do aparelho e do servidor podem diferir um pouco.
        assertEquals("a", matchPending(list, "c1", "oi", null, 0, createdAt = NOW - 4.minutes)?.localId)
        assertNull(matchPending(list, "c1", "oi", null, 0, createdAt = NOW - 6.minutes))
        // Sem horário (evento de tempo real) não há o que comparar.
        assertEquals("a", matchPending(list, "c1", "oi", null, 0, createdAt = null)?.localId)
    }
}

/** Entrega falsa: cada envio espera o teste dizer como ele termina. */
private class FakeDeliver {
    class Call(val message: PendingMessage, val onProgress: (Long, Long?) -> Unit) {
        val result = CompletableDeferred<Unit>()
        var cancelled = false
    }

    val calls = mutableListOf<Call>()
    val contents get() = calls.map { it.message.content }

    suspend fun deliver(message: PendingMessage, onProgress: (Long, Long?) -> Unit) {
        val call = Call(message, onProgress)
        calls += call
        try {
            call.result.await()
        } catch (e: CancellationException) {
            call.cancelled = true
            throw e
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxTest {
    private val fake = FakeDeliver()

    private fun TestScope.outbox() = Outbox(backgroundScope, now = { NOW }, deliver = fake::deliver)

    private fun Outbox.statuses() = pending.value.map { it.status }

    private fun Outbox.say(channel: String, text: String, files: List<OutgoingFile> = emptyList()) = enqueue(channel, text, null, files)

    @Test
    fun enfileirarMostraAMensagemNaHoraAparadaESemEnviarAindaNada() = runTest {
        val outbox = outbox()
        val first = outbox.enqueue("c1", "  oi  ", "m1", emptyList())
        val second = outbox.say("c1", "de novo")

        val shown = outbox.pending.value
        assertEquals(listOf("oi", "de novo"), shown.map { it.content })
        assertNotEquals(first, second)
        assertEquals(first, shown[0].localId)
        assertEquals("m1", shown[0].replyToId)
        assertEquals(NOW, shown[0].enqueuedAt)
        assertEquals(listOf(PendingStatus.Queued, PendingStatus.Queued), outbox.statuses())
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun umEnvioPorVezEmCadaCanalNaOrdemEmQueForamMandados() = runTest {
        val outbox = outbox()
        outbox.say("c1", "a")
        outbox.say("c1", "b")
        outbox.say("c2", "x")
        outbox.say("c1", "c")
        runCurrent()

        // O primeiro de c1 e o de c2 saem juntos; o resto de c1 espera.
        assertEquals(listOf("a", "x"), fake.contents)
        assertEquals(listOf(PendingStatus.Sending, PendingStatus.Queued, PendingStatus.Sending, PendingStatus.Queued), outbox.statuses())

        fake.calls[0].result.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "x", "b"), fake.contents)
        assertEquals(listOf("b", "x", "c"), outbox.pending.value.map { it.content })

        fake.calls[2].result.complete(Unit) // b
        runCurrent()
        assertEquals(listOf("a", "x", "b", "c"), fake.contents)
        fake.calls[3].result.complete(Unit) // c
        fake.calls[1].result.complete(Unit) // x
        runCurrent()
        assertTrue(outbox.pending.value.isEmpty())
    }

    @Test
    fun falhaPausaACadaDoCanalESoOutroCanalSegue() = runTest {
        val outbox = outbox()
        outbox.say("c1", "a")
        outbox.say("c1", "b")
        outbox.say("c2", "x")
        runCurrent()

        fake.calls[0].result.completeExceptionally(api(403, "Proibido", "sem permissão"))
        runCurrent()

        assertEquals(PendingStatus.Failed(SendFailure.Rejected, "sem permissão"), outbox.pending.value[0].status)
        assertEquals(PendingStatus.Queued, outbox.pending.value[1].status) // b não passa na frente de a
        assertEquals(PendingStatus.Sending, outbox.pending.value[2].status) // c2 não é afetado
        assertEquals(listOf("a", "x"), fake.contents)
    }

    @Test
    fun umRateLimitFazExatamenteUmPedidoENadaMaisSozinho() = runTest {
        val outbox = outbox()
        outbox.say("c1", "a")
        outbox.say("c1", "b")
        runCurrent()
        fake.calls[0].result.completeExceptionally(api(429, "Limite de requisições excedido", "tente depois"))
        runCurrent()

        // Nem o tempo faz a fila andar: reenviar é uma decisão do usuário (e o servidor pediu calma).
        advanceTimeBy(24.hours)
        runCurrent()

        assertEquals(listOf("a"), fake.contents)
        assertEquals(listOf(PendingStatus.Failed(SendFailure.RateLimited), PendingStatus.Queued), outbox.statuses())
    }

    @Test
    fun respostaQueNaoConfirmaPausaEFicaIncertaSemRepetir() = runTest {
        val outbox = outbox()
        outbox.say("c1", "a")
        outbox.say("c1", "b")
        runCurrent()
        fake.calls[0].result.completeExceptionally(api(502))
        runCurrent()
        advanceTimeBy(24.hours)

        assertEquals(listOf(PendingStatus.Uncertain, PendingStatus.Queued), outbox.statuses())
        assertEquals(listOf("a"), fake.contents)
    }

    @Test
    fun reenviarPoeDeNovoNaFilaEAFilaSegueDepois() = runTest {
        val outbox = outbox()
        val a = outbox.say("c1", "a")
        outbox.say("c1", "b")
        runCurrent()
        fake.calls[0].result.completeExceptionally(api(403))
        runCurrent()

        assertTrue(outbox.retry(a))
        assertEquals(PendingStatus.Queued, outbox.pending.value[0].status)
        runCurrent()
        assertEquals(listOf("a", "a"), fake.contents)
        fake.calls[1].result.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "a", "b"), fake.contents) // b só sai depois de a ter saído
        fake.calls[2].result.complete(Unit)
        runCurrent()
        assertTrue(outbox.pending.value.isEmpty())
    }

    @Test
    fun incertaSoReenviaDepoisDeConfirmadaPeloUsuario() = runTest {
        val outbox = outbox()
        val a = outbox.say("c1", "a")
        runCurrent()
        fake.calls[0].result.completeExceptionally(api(500))
        runCurrent()

        assertFalse(outbox.retry(a)) // sem confirmação: pode duplicar
        runCurrent()
        assertEquals(PendingStatus.Uncertain, outbox.pending.value.single().status)
        assertEquals(1, fake.calls.size)

        assertTrue(outbox.retry(a, confirmed = true))
        runCurrent()
        assertEquals(2, fake.calls.size)
        assertEquals(PendingStatus.Sending, outbox.pending.value.single().status)
    }

    @Test
    fun reenviarOQueNaoFalhouNaoFazNada() = runTest {
        val outbox = outbox()
        val a = outbox.say("c1", "a")
        val b = outbox.say("c1", "b")
        runCurrent()
        assertFalse(outbox.retry(a)) // saindo
        assertFalse(outbox.retry(b)) // esperando a vez
        assertFalse(outbox.retry("local-que-nao-existe"))
        runCurrent()
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun retirarDaFilaUmaMensagemEmEsperaNaoFazPedidoAlgum() = runTest {
        val outbox = outbox()
        outbox.say("c1", "a")
        val b = outbox.say("c1", "b")
        runCurrent()

        outbox.withdraw(b)
        assertEquals(listOf("a"), outbox.pending.value.map { it.content })
        fake.calls[0].result.complete(Unit)
        runCurrent()

        assertEquals(listOf("a"), fake.contents) // b nunca foi enviada
        assertTrue(outbox.pending.value.isEmpty())
    }

    @Test
    fun descartarAFalhadaLiberaAFilaQueEsperavaPorElaMasNaoEnviaOQueFoiDescartada() = runTest {
        val outbox = outbox()
        val a = outbox.say("c1", "a")
        outbox.say("c1", "b")
        runCurrent()
        fake.calls[0].result.completeExceptionally(api(413))
        runCurrent()
        assertEquals(listOf("a"), fake.contents)

        outbox.withdraw(a)
        runCurrent()

        assertEquals(listOf("a", "b"), fake.contents)
        assertEquals(listOf("b"), outbox.pending.value.map { it.content })
        outbox.withdraw("local-que-nao-existe") // sem efeito
    }

    @Test
    fun cancelarAntesDeTodosOsBytesDescartaEAFilaSegue() = runTest {
        val outbox = outbox()
        val a = outbox.say("c1", "a", listOf(file()))
        outbox.say("c1", "b")
        runCurrent()
        fake.calls[0].onProgress(40, 100)
        assertEquals(0.4f, outbox.pending.value[0].progress)

        outbox.withdraw(a)
        runCurrent()

        assertTrue(fake.calls[0].cancelled)
        assertEquals(listOf("b"), outbox.pending.value.map { it.content }) // a sumiu
        assertEquals(listOf("a", "b"), fake.contents) // e b saiu em seguida
    }

    @Test
    fun cancelarDepoisDeTodosOsBytesFicaIncertaEPausaAFila() = runTest {
        val outbox = outbox()
        val a = outbox.say("c1", "a", listOf(file()))
        outbox.say("c1", "b")
        runCurrent()
        fake.calls[0].onProgress(100, 100)

        outbox.withdraw(a)
        runCurrent()
        advanceTimeBy(1.hours)

        assertTrue(fake.calls[0].cancelled)
        assertEquals(listOf(PendingStatus.Uncertain, PendingStatus.Queued), outbox.statuses())
        assertEquals(listOf("a"), fake.contents)
    }

    @Test
    fun semSaberOTotalQualquerByteEntregueJaCountaComoEnviado() = runTest {
        val outbox = outbox()
        val a = outbox.say("c1", "a", listOf(file()))
        val b = outbox.say("c2", "b", listOf(file()))
        runCurrent()
        fake.calls[0].onProgress(10, null)
        fake.calls[1].onProgress(0, null)

        outbox.withdraw(a)
        outbox.withdraw(b)
        runCurrent()

        assertEquals(listOf(PendingStatus.Uncertain), outbox.statuses())
        assertEquals("a", outbox.pending.value.single().content)
    }

    @Test
    fun progressoSoMudaACada1PorCentoESoComAnexo() = runTest {
        val outbox = outbox()
        outbox.say("c1", "com arquivo", listOf(file()))
        outbox.say("c2", "só texto")
        runCurrent()
        assertEquals(0f, outbox.pending.value[0].progress) // começou, nada enviado
        assertNull(outbox.pending.value[1].progress)

        fake.calls[0].onProgress(5, 1000) // 0,5%
        assertEquals(0f, outbox.pending.value[0].progress)
        fake.calls[0].onProgress(20, 1000)
        assertEquals(0.02f, outbox.pending.value[0].progress)
        fake.calls[0].onProgress(1000, 1000)
        assertEquals(1f, outbox.pending.value[0].progress)
        fake.calls[1].onProgress(50, 100)
        assertNull(outbox.pending.value[1].progress)
    }

    @Test
    fun comArquivoSemTamanhoOEnvioNaoTemPorcentagemMasQualquerByteJaContaComoEntregue() = runTest {
        val outbox = outbox()
        val unknown = sizedFile("a.bin", null)
        val a = outbox.say("c1", "a", listOf(file(), unknown)) // um sem tamanho basta: o corpo não tem total
        runCurrent()
        assertEquals(PendingStatus.Sending, outbox.pending.value[0].status)
        assertNull(outbox.pending.value[0].progress) // a tela mostra a barra indeterminada

        fake.calls[0].onProgress(500, null)
        assertNull(outbox.pending.value[0].progress)

        outbox.withdraw(a)
        runCurrent()
        assertEquals(PendingStatus.Uncertain, outbox.pending.value.single().status) // sem total, cancelar já não garante nada
    }

    @Test
    fun avisoDeProgressoQueChegaDepoisDeTerminarNaoMexeEmNada() = runTest {
        val outbox = outbox()
        val a = outbox.say("c1", "a", listOf(file()))
        outbox.say("c2", "b", listOf(file()))
        runCurrent()
        fake.calls[0].onProgress(100, 100)
        outbox.withdraw(a) // incerta
        runCurrent()
        assertEquals(PendingStatus.Uncertain, outbox.pending.value[0].status)

        fake.calls[0].onProgress(100, 100) // o motor ainda esvaziava o buffer
        fake.calls[1].onProgress(10, 0) // sem total que sirva de divisor: não há fração a mostrar
        outbox.confirm("c2", "b", null, 1)
        fake.calls[1].onProgress(500, 1000) // a entrada já saiu da lista

        assertNull(outbox.pending.value[0].progress)
        assertEquals(1, outbox.pending.value.size)
    }

    @Test
    fun falhaLimpaOProgresso() = runTest {
        val outbox = outbox()
        outbox.say("c1", "a", listOf(file()))
        runCurrent()
        fake.calls[0].onProgress(500, 1000)
        fake.calls[0].result.completeExceptionally(api(403))
        runCurrent()
        assertNull(outbox.pending.value.single().progress)
    }

    @Test
    fun mensagemRecebidaTiraAEntradaQueEstavaSaindoSemPararAFila() = runTest {
        val outbox = outbox()
        outbox.say("c1", "oi")
        outbox.say("c1", "depois")
        runCurrent()

        assertTrue(outbox.confirm("c1", " oi ", null, 0))
        assertEquals(listOf("depois"), outbox.pending.value.map { it.content })

        // O envio que ficou sem dono ainda termina, e a fila só segue depois dele.
        runCurrent()
        assertEquals(listOf("oi"), fake.contents)
        fake.calls[0].result.completeExceptionally(api(500)) // nem a falha dele ressuscita a entrada
        runCurrent()
        assertEquals(listOf("oi", "depois"), fake.contents)
        assertEquals(listOf(PendingStatus.Sending), outbox.statuses())
    }

    @Test
    fun mensagemRecebidaNaoTiraQuemNaoSaiu() = runTest {
        val outbox = outbox()
        outbox.say("c1", "oi")
        val second = outbox.say("c1", "oi")
        runCurrent()
        fake.calls[0].result.completeExceptionally(api(403))
        runCurrent()

        // a falhou (nunca foi criada) e b espera: nenhuma pode ser a mensagem que chegou.
        assertFalse(outbox.confirm("c1", "oi", null, 0))
        assertEquals(2, outbox.pending.value.size)
        outbox.withdraw(second)
    }

    @Test
    fun confirmarAIncertaLiberaAFilaQueEsperavaPorEla() = runTest {
        val outbox = outbox()
        outbox.say("c1", "a")
        outbox.say("c1", "b")
        runCurrent()
        fake.calls[0].result.completeExceptionally(api(500))
        runCurrent()

        assertTrue(outbox.confirm("c1", "a", null, 0, createdAt = NOW + 1.minutes))
        runCurrent()

        assertEquals(listOf("a", "b"), fake.contents)
        assertEquals(listOf(PendingStatus.Sending), outbox.statuses())
    }

    @Test
    fun cancelamentoQueNaoFoiDoUsuarioNaoSomeComAMensagem() = runTest {
        val outbox = outbox()
        outbox.say("c1", "a", listOf(file()))
        outbox.say("c2", "b", listOf(file()))
        runCurrent()
        fake.calls[1].onProgress(100, 100)

        fake.calls[0].result.completeExceptionally(CancellationException("fechou por dentro"))
        fake.calls[1].result.completeExceptionally(CancellationException("fechou por dentro"))
        runCurrent()

        assertEquals(PendingStatus.Failed(SendFailure.NotSent), outbox.pending.value[0].status)
        assertEquals(PendingStatus.Uncertain, outbox.pending.value[1].status)
    }

    @Test
    fun encerrarASessaoCancelaOsEnviosSemMexerNoEstado() = runTest {
        val outbox = outbox()
        outbox.say("c1", "a")
        runCurrent()

        backgroundScope.cancel() // a conta saiu ou o app travou
        runCurrent()

        assertTrue(fake.calls[0].cancelled)
        assertEquals(listOf(PendingStatus.Sending), outbox.statuses()) // não vira falha: a tela nem existe mais
    }
}
