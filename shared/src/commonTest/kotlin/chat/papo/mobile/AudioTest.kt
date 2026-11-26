package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.media.AudioSpeed
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.formatClock
import chat.papo.mobile.media.isAudioAttachment
import chat.papo.mobile.media.playableAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioTest {
    private val server = ServerUrl("https://papo.test")

    private fun attachment(
        id: String = "att-1",
        mimeType: String = "audio/ogg",
        fileName: String = "recado.ogg",
        moderationStatus: String? = null,
    ) = AttachmentDto(id = id, mimeType = mimeType, fileName = fileName, sizeBytes = 48_000, moderationStatus = moderationStatus)

    // isAudioAttachment: MIME

    @Test
    fun qualquerSubtipoDeAudioToca() {
        assertTrue(isAudioAttachment("audio/ogg", "recado.ogg"))
        assertTrue(isAudioAttachment("audio/mpeg", "musica.mp3"))
        assertTrue(isAudioAttachment("audio/mp4", "nota.m4a"))
        assertTrue(isAudioAttachment("audio/x-wav", "som"))
    }

    @Test
    fun oMimeIgnoraCaixaEEspacosNasPontas() {
        assertTrue(isAudioAttachment("  Audio/OGG ", "recado"))
    }

    @Test
    fun audioSemSubtipoOuComSubtipoInvalidoNaoToca() {
        assertFalse(isAudioAttachment("audio/", "recado.ogg"))
        assertFalse(isAudioAttachment("audio/ ogg", "recado.ogg"))
        assertFalse(isAudioAttachment("xaudio/ogg", "recado.ogg"))
    }

    @Test
    fun outrosTiposNaoSaoAudio() {
        assertFalse(isAudioAttachment("image/png", "foto.png"))
        assertFalse(isAudioAttachment("video/mp4", "filme.mp4"))
        assertFalse(isAudioAttachment("", ""))
    }

    // isAudioAttachment: nome de gravação do web

    @Test
    fun gravacaoDoWebToca_mesmoQuandoOServidorFarejouVideo() {
        assertTrue(isAudioAttachment("video/webm", "audio_20261126-1042.webm"))
        assertTrue(isAudioAttachment("video/ogg", "audio_20261126-1042.ogg"))
        assertTrue(isAudioAttachment("application/octet-stream", "audio_x.opus"))
    }

    @Test
    fun nomeDeGravacaoIgnoraCaixaENaoPedeNadaNoMeio() {
        assertTrue(isAudioAttachment("video/webm", "AUDIO_1.WEBM"))
        assertTrue(isAudioAttachment("video/webm", "audio_.ogg"))
        assertTrue(isAudioAttachment("video/webm", "audio_meu recado.final.mp3"))
    }

    @Test
    fun nomeQueSoParecidoComGravacaoNaoToca() {
        assertFalse(isAudioAttachment("video/webm", "meu_audio_1.webm"))
        assertFalse(isAudioAttachment("video/webm", "audio_1.mp4"))
        assertFalse(isAudioAttachment("video/webm", "audio_1.webm.exe"))
        assertFalse(isAudioAttachment("video/webm", "audio.webm"))
    }

    // isAudioAttachment: envio antigo, com tipo genérico

    @Test
    fun tipoGenericoComExtensaoDeAudioToca() {
        assertTrue(isAudioAttachment("application/octet-stream", "musica.MP3"))
        assertTrue(isAudioAttachment(" application/octet-stream ", "livro.m4b"))
        assertTrue(isAudioAttachment("application/octet-stream", "som.flac"))
    }

    @Test
    fun tipoGenericoSemExtensaoDeAudioNaoToca() {
        assertFalse(isAudioAttachment("application/octet-stream", "relatorio.pdf"))
        assertFalse(isAudioAttachment("application/octet-stream", "mp3"))
    }

    @Test
    fun extensaoDeAudioSoValeComTipoGenerico() {
        assertFalse(isAudioAttachment("application/pdf", "musica.mp3"))
        assertFalse(isAudioAttachment("video/mp4", "musica.mp3"))
    }

    // playableAudio

    @Test
    fun anexoDeAudioViraMidiaDoServidorDaConta() {
        assertEquals(
            PlayableMedia(
                key = "attachment:att-1",
                url = "https://papo.test/attachments/att-1",
                mimeType = "audio/ogg",
                title = "recado.ogg",
                width = null,
                height = null,
            ),
            playableAudio(server, attachment()),
        )
    }

    @Test
    fun audioDeOutroServidorNuncaSaiDoServidorDaConta() {
        val other = ServerUrl("https://outro.test")
        assertEquals("https://outro.test/attachments/att-1", playableAudio(other, attachment())?.url)
    }

    @Test
    fun anexoQueNaoEAudioNaoViraMidia() {
        assertNull(playableAudio(server, attachment(mimeType = "video/mp4", fileName = "filme.mp4")))
    }

    @Test
    fun anexoBloqueadoNaoToca() {
        assertNull(playableAudio(server, attachment(moderationStatus = "blocked")))
    }

    @Test
    fun anexoSensivelAindaEAudioTocavel_aTelaEscondeAteOToque() {
        assertEquals("attachment:att-1", playableAudio(server, attachment(moderationStatus = "sensitive"))?.key)
    }

    @Test
    fun idForaDoFormatoDeCaminhoNaoViraMidia() {
        assertNull(playableAudio(server, attachment(id = "../outro")))
        assertNull(playableAudio(server, attachment(id = "")))
    }

    // formatClock

    @Test
    fun relogioTrunca_osSegundos() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:00", formatClock(999))
        assertEquals("0:01", formatClock(1_000))
        assertEquals("1:05", formatClock(65_900))
        assertEquals("10:00", formatClock(600_000))
        assertEquals("59:59", formatClock(3_599_999))
    }

    @Test
    fun relogioMostraHorasAPartirDeUmaHora() {
        assertEquals("1:00:00", formatClock(3_600_000))
        assertEquals("1:02:05", formatClock(3_725_000))
        assertEquals("12:00:09", formatClock(43_209_000))
    }

    @Test
    fun relogioNegativoValeZero() {
        assertEquals("0:00", formatClock(-1))
        assertEquals("0:00", formatClock(Long.MIN_VALUE))
    }

    // AudioSpeed

    @Test
    fun velocidadesAlternamEmCicloDeUmaCasaAteDois() {
        assertEquals(listOf(1f, 1.5f, 2f), AudioSpeed.entries.map { it.factor })
        assertEquals(AudioSpeed.Fast, AudioSpeed.Normal.next())
        assertEquals(AudioSpeed.Faster, AudioSpeed.Fast.next())
        assertEquals(AudioSpeed.Normal, AudioSpeed.Faster.next())
    }
}
