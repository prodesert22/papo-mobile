package chat.papo.mobile

import chat.papo.mobile.chat.Message
import chat.papo.mobile.chat.continuesGroup
import chat.papo.mobile.core.parseInstant
import chat.papo.mobile.data.api.ExternalAuthorDto
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageGroupingTest {
    private val utc = TimeZone.UTC

    private fun message(
        at: String?,
        authorId: String? = "ana",
        replyTo: String? = null,
        webhookId: String? = null,
        webhookName: String? = null,
    ) = Message(
        id = "m-$at-$authorId-$webhookId",
        channelId = "c1",
        authorId = authorId,
        content = "oi",
        createdAt = parseInstant(at),
        createdAtIso = at.orEmpty(),
        edited = false,
        replyTo = replyTo,
        webhookId = webhookId,
        externalAuthor = webhookName?.let { ExternalAuthorDto(it) },
        attachments = emptyList(),
        embeds = emptyList(),
        reactions = emptyList(),
        userReactions = emptyList(),
    )

    private fun continues(older: Message, newer: Message) = continuesGroup(older, newer, utc)

    // Janela de 10 minutos

    @Test
    fun mesmoAutorNaMesmaHoraAgrupa() {
        assertTrue(continues(message("2026-11-25T12:00:00Z"), message("2026-11-25T12:00:00Z")))
    }

    @Test
    fun mesmoAutorAteDezMinutosAgrupaEDezMinutosEUmSegundoNao() {
        val older = message("2026-11-25T12:00:00Z")
        assertTrue(continues(older, message("2026-11-25T12:09:59Z")))
        assertTrue(continues(older, message("2026-11-25T12:10:00Z")))
        assertFalse(continues(older, message("2026-11-25T12:10:01Z")))
    }

    @Test
    fun maisNovaComHorarioAnteriorNaoAgrupa() {
        assertFalse(continues(message("2026-11-25T12:05:00Z"), message("2026-11-25T12:04:59Z")))
    }

    @Test
    fun milissegundosContamNaFronteira() {
        val older = message("2026-11-25T12:00:00.500Z")
        assertTrue(continues(older, message("2026-11-25T12:10:00.500Z")))
        assertFalse(continues(older, message("2026-11-25T12:10:00.501Z")))
    }

    // Autor

    @Test
    fun autoresDiferentesNaoAgrupam() {
        assertFalse(continues(message("2026-11-25T12:00:00Z", authorId = "ana"), message("2026-11-25T12:01:00Z", authorId = "bia")))
    }

    @Test
    fun semAutorConhecidoNuncaAgrupa() {
        assertFalse(continues(message("2026-11-25T12:00:00Z", authorId = null), message("2026-11-25T12:01:00Z", authorId = null)))
        assertFalse(continues(message("2026-11-25T12:00:00Z", authorId = "ana"), message("2026-11-25T12:01:00Z", authorId = null)))
        assertFalse(continues(message("2026-11-25T12:00:00Z", authorId = null), message("2026-11-25T12:01:00Z", authorId = "ana")))
    }

    // Resposta

    @Test
    fun respostaComecaUmGrupoNovo() {
        assertFalse(continues(message("2026-11-25T12:00:00Z"), message("2026-11-25T12:01:00Z", replyTo = "m0")))
    }

    @Test
    fun mensagemDepoisDeUmaRespostaAgrupaComEla() {
        assertTrue(continues(message("2026-11-25T12:00:00Z", replyTo = "m0"), message("2026-11-25T12:01:00Z")))
    }

    // Webhook

    @Test
    fun mesmoWebhookAgrupa() {
        val older = message("2026-11-25T12:00:00Z", authorId = null, webhookId = "w1", webhookName = "Robô")
        val newer = message("2026-11-25T12:02:00Z", authorId = null, webhookId = "w1", webhookName = "Robô")
        assertTrue(continues(older, newer))
    }

    @Test
    fun webhooksDiferentesNaoAgrupam() {
        val older = message("2026-11-25T12:00:00Z", authorId = null, webhookId = "w1", webhookName = "Robô")
        val newer = message("2026-11-25T12:02:00Z", authorId = null, webhookId = "w2", webhookName = "Robô")
        assertFalse(continues(older, newer))
    }

    @Test
    fun mesmoWebhookComNomeDeExibicaoDiferenteNaoAgrupa() {
        val older = message("2026-11-25T12:00:00Z", authorId = null, webhookId = "w1", webhookName = "Robô")
        val newer = message("2026-11-25T12:02:00Z", authorId = null, webhookId = "w1", webhookName = "Outro nome")
        assertFalse(continues(older, newer))
    }

    @Test
    fun pessoaEWebhookNaoAgrupamNemNumSentidoNemNoOutro() {
        val person = message("2026-11-25T12:00:00Z", authorId = "ana")
        val webhook = message("2026-11-25T12:01:00Z", authorId = null, webhookId = "w1", webhookName = "Robô")
        assertFalse(continues(person, webhook))
        assertFalse(continues(message("2026-11-25T12:00:00Z", authorId = null, webhookId = "w1", webhookName = "Robô"), message("2026-11-25T12:01:00Z", authorId = "ana")))
    }

    @Test
    fun webhookQueEscolheOMesmoNomeSemExternalAuthorAgrupaPeloId() {
        val older = message("2026-11-25T12:00:00Z", authorId = null, webhookId = "w1")
        val newer = message("2026-11-25T12:02:00Z", authorId = null, webhookId = "w1")
        assertTrue(continues(older, newer))
    }

    // Sem horário

    @Test
    fun semHorarioNaoAgrupa() {
        assertFalse(continues(message(null), message("2026-11-25T12:01:00Z")))
        assertFalse(continues(message("2026-11-25T12:00:00Z"), message(null)))
        assertFalse(continues(message(null), message(null)))
    }

    @Test
    fun horarioQueNaoEUmInstanteNaoAgrupa() {
        assertFalse(continues(message("ontem"), message("2026-11-25T12:01:00Z")))
    }

    // Virada do dia

    @Test
    fun viradaDoDiaQuebraOGrupoMesmoDentroDaJanela() {
        assertFalse(continues(message("2026-11-25T23:55:00Z"), message("2026-11-26T00:03:00Z")))
    }

    @Test
    fun odiaVemDoFusoInformado() {
        val older = message("2026-11-25T23:55:00Z")
        val newer = message("2026-11-26T00:03:00Z")
        // Em São Paulo (UTC-3) as duas são do mesmo dia: 20:55 e 21:03.
        assertTrue(continuesGroup(older, newer, TimeZone.of("America/Sao_Paulo")))
        assertFalse(continuesGroup(older, newer, utc))
    }

    @Test
    fun semFusoInformadoUsaODoAparelho() {
        // Meio-dia e cinco minutos depois: não cruzam a meia-noite em fuso nenhum.
        assertTrue(continuesGroup(message("2026-11-25T12:00:00Z"), message("2026-11-25T12:05:00Z")))
    }
}
