package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.data.api.AttachmentDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedMediaDto
import chat.papo.mobile.media.PlayableMedia
import chat.papo.mobile.media.PlaybackErrorKind
import chat.papo.mobile.media.PlaybackFailure
import chat.papo.mobile.media.classifyPlayback
import chat.papo.mobile.media.playableAttachment
import chat.papo.mobile.media.relayVideo
import chat.papo.mobile.media.retryDelay
import chat.papo.mobile.media.videoAspect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlaybackTest {
    private val server = ServerUrl("https://papo.test")

    private fun attachment(
        id: String = "att-1",
        mimeType: String = "video/mp4",
        moderationStatus: String? = null,
    ) = AttachmentDto(
        id = id,
        mimeType = mimeType,
        fileName = "passeio.mp4",
        sizeBytes = 30_000_000,
        width = 1280,
        height = 720,
        moderationStatus = moderationStatus,
    )

    private fun embed(
        id: String = "emb-1",
        title: String? = "Passeio",
        video: EmbedMediaDto? = EmbedMediaDto("https://cdn.example.test/passeio.mp4", "video/mp4", 640, 360),
    ) = EmbedDto(id = id, title = title, video = video)

    private fun embedVideo(url: String? = "https://cdn.example.test/passeio.mp4", mimeType: String? = "video/mp4") =
        embed(video = EmbedMediaDto(url, mimeType, 640, 360))

    // playableAttachment

    @Test
    fun anexoDeVideoViraMidiaDoServidorDaConta() {
        assertEquals(
            PlayableMedia(
                key = "attachment:att-1",
                url = "https://papo.test/attachments/att-1",
                mimeType = "video/mp4",
                title = "passeio.mp4",
                width = 1280,
                height = 720,
            ),
            playableAttachment(server, attachment()),
        )
    }

    @Test
    fun qualquerVideoToca() {
        // O servidor só fareja o MIME: se o aparelho decodifica é problema do player, depois.
        listOf("video/mp4", "video/webm", "video/ogg", "video/x-matroska", "video/quicktime").forEach {
            assertNotNull(playableAttachment(server, attachment(mimeType = it)), it)
        }
    }

    @Test
    fun anexoQueNaoEVideoNaoToca() {
        listOf("image/png", "audio/mpeg", "application/pdf", "application/octet-stream", "").forEach {
            assertNull(playableAttachment(server, attachment(mimeType = it)), it)
        }
    }

    @Test
    fun anexoBloqueadoNaoToca() {
        assertNull(playableAttachment(server, attachment(moderationStatus = "blocked")))
    }

    @Test
    fun anexoEmOutroEstadoDeModeracaoToca() {
        // Sensível fica escondido pela tela até o toque; quem decide é a tela, não esta função.
        listOf(null, "pending", "processing", "clean", "sensitive", "failed").forEach {
            assertNotNull(playableAttachment(server, attachment(moderationStatus = it)), it.toString())
        }
    }

    @Test
    fun idDeAnexoForaDoPadraoNaoToca() {
        listOf("", "a/b", "../x", "a?b", "a#b", "a b", "a@b", "a:b", "ação").forEach {
            assertNull(playableAttachment(server, attachment(id = it)), it)
        }
    }

    @Test
    fun idDeAnexoComoUuidToca() {
        val id = "0f8c6a52-3b1e-4c7a-9d2e-5a6b7c8d9e0f"
        assertEquals("https://papo.test/attachments/$id", playableAttachment(server, attachment(id = id))?.url)
        assertNotNull(playableAttachment(server, attachment(id = "A_b-9")))
    }

    @Test
    fun urlDoAnexoSegueOEnderecoEOPrefixoDoServidorDaConta() {
        assertEquals(
            "https://papo.test/api/attachments/att-1",
            playableAttachment(ServerUrl("https://papo.test/api"), attachment())?.url,
        )
        assertEquals(
            "http://10.0.2.2:8080/attachments/att-1",
            playableAttachment(ServerUrl("http://10.0.2.2:8080"), attachment())?.url,
        )
    }

    // relayVideo

    @Test
    fun videoDeEmbedViraRelayNoServidorDaConta() {
        assertEquals(
            PlayableMedia(
                key = "embed:emb-1",
                url = "https://papo.test/embeds/emb-1/video",
                mimeType = "video/mp4",
                title = "Passeio",
                width = 640,
                height = 360,
            ),
            relayVideo(server, embed()),
        )
    }

    @Test
    fun urlDoRelayNuncaEADeOrigem() {
        val media = relayVideo(ServerUrl("https://papo.test/api"), embed())
        assertEquals("https://papo.test/api/embeds/emb-1/video", media?.url)
    }

    @Test
    fun relayAceitaMp4WebmEOgg() {
        listOf("video/mp4", "video/webm", "video/ogg").forEach {
            assertEquals(it, relayVideo(server, embedVideo(mimeType = it))?.mimeType, it)
        }
    }

    @Test
    fun embedSemVideoNaoToca() {
        assertNull(relayVideo(server, embed(video = null)))
    }

    @Test
    fun videoSemMimeOuComMimeForaDaListaNaoToca() {
        listOf(null, "", "video/quicktime", "video/x-matroska", "image/png", "text/html", "Video/MP4", "video/mp4; codecs=avc1")
            .forEach { assertNull(relayVideo(server, embedVideo(mimeType = it)), it.toString()) }
    }

    @Test
    fun videoSemUrlNaoToca() {
        assertNull(relayVideo(server, embedVideo(url = null)))
    }

    @Test
    fun videoQueNaoEHttpsNaoToca() {
        listOf("http://cdn.example.test/v.mp4", "//cdn.example.test/v.mp4", "/v.mp4", "ftp://cdn.example.test/v.mp4", "")
            .forEach { assertNull(relayVideo(server, embedVideo(url = it)), it) }
    }

    @Test
    fun esquemaHttpsNaoDependeDaCaixa() {
        assertNotNull(relayVideo(server, embedVideo(url = "HTTPS://cdn.example.test/v.mp4")))
    }

    @Test
    fun iframeDoYoutubeNaoToca() {
        listOf(
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
            "https://youtube-nocookie.com/embed/dQw4w9WgXcQ",
            "https://WWW.YouTube.com:443/embed/dQw4w9WgXcQ?start=3#t",
            "https://user:senha@www.youtube.com/embed/dQw4w9WgXcQ",
        ).forEach {
            // Mesmo que o servidor mandasse um MIME de arquivo junto, a página do YouTube não é arquivo.
            assertNull(relayVideo(server, embedVideo(url = it, mimeType = "video/mp4")), it)
            assertNull(relayVideo(server, embedVideo(url = it, mimeType = null)), it)
        }
    }

    @Test
    fun hostQueApenasParecidoComYoutubeToca() {
        listOf(
            "https://www.youtube.com.example.test/v.mp4",
            "https://notyoutube.com/v.mp4",
            "https://youtube.com@cdn.example.test/v.mp4",
            "https://cdn.example.test/www.youtube.com/v.mp4",
            "https://cdn.example.test/v.mp4?from=youtu.be",
            "https://cdn.example.test/v.mp4#youtube.com",
        ).forEach { assertNotNull(relayVideo(server, embedVideo(url = it)), it) }
    }

    @Test
    fun embedSemTituloTocaComTituloVazio() {
        assertEquals("", relayVideo(server, embed(title = null))?.title)
    }

    @Test
    fun dimensoesSaemDoVideoNaoDoEmbed() {
        val media = relayVideo(server, embed(video = EmbedMediaDto("https://cdn.example.test/v.mp4", "video/webm")))
        assertNull(media?.width)
        assertNull(media?.height)
        assertEquals(640, relayVideo(server, embed())?.width)
        assertEquals(360, relayVideo(server, embed())?.height)
    }

    @Test
    fun idDeEmbedForaDoPadraoNaoToca() {
        listOf("", "a/b", "../x", "a?b", "a b").forEach {
            assertNull(relayVideo(server, embed(id = it)), it)
        }
    }

    // videoAspect

    @Test
    fun semDimensoesValidasAProporcaoE16Por9() {
        val padrao = 16f / 9f
        listOf(
            null to null, 1280 to null, null to 720,
            0 to 720, 1280 to 0, -1280 to 720, 1280 to -720, 0 to 0,
        ).forEach { (w, h) -> assertEquals(padrao, videoAspect(w, h), 0.0001f, "$w x $h") }
    }

    @Test
    fun proporcaoDentroDoLimiteEALarguraSobreAltura() {
        assertEquals(16f / 9f, videoAspect(1920, 1080), 0.0001f)
        assertEquals(4f / 3f, videoAspect(640, 480), 0.0001f)
        assertEquals(1f, videoAspect(500, 500), 0.0001f)
        assertEquals(9f / 16f, videoAspect(720, 1280), 0.0001f)
    }

    @Test
    fun proporcaoNoLimiteNaoMuda() {
        assertEquals(0.5f, videoAspect(500, 1000), 0.0001f)
        assertEquals(2.5f, videoAspect(2500, 1000), 0.0001f)
    }

    @Test
    fun proporcaoMuitoAltaOuMuitoLargaFicaNoLimite() {
        assertEquals(0.5f, videoAspect(100, 1000), 0.0001f)
        assertEquals(2.5f, videoAspect(3000, 1000), 0.0001f)
    }

    // classifyPlayback

    private fun classify(status: Int?, kind: PlaybackErrorKind = PlaybackErrorKind.Io) = classifyPlayback(status, kind)

    @Test
    fun statusDoContratoTemMotivoProprio() {
        assertEquals(PlaybackFailure.SessionEnded, classify(401))
        assertEquals(PlaybackFailure.Forbidden, classify(403))
        assertEquals(PlaybackFailure.NotFound, classify(404))
        assertEquals(PlaybackFailure.Gone, classify(410))
        assertEquals(PlaybackFailure.Gone, classify(416))
        assertEquals(PlaybackFailure.RateLimited, classify(429))
    }

    @Test
    fun sessaoEncerradaE401ESemPermissaoE403ENuncaUmPeloOutro() {
        assertEquals(PlaybackFailure.SessionEnded, classify(401, PlaybackErrorKind.Decoding))
        assertEquals(PlaybackFailure.Forbidden, classify(403, PlaybackErrorKind.Decoding))
        assertEquals(PlaybackFailure.SessionEnded, classify(401, PlaybackErrorKind.Io))
        assertEquals(PlaybackFailure.Forbidden, classify(403, PlaybackErrorKind.Io))
    }

    @Test
    fun erroDeServidorEIndisponivel() {
        listOf(500, 502, 503, 504, 599).forEach { assertEquals(PlaybackFailure.Unavailable, classify(it), "$it") }
    }

    @Test
    fun outro4xxForaDoContratoViraNaoEncontrado() {
        listOf(400, 405, 409, 413, 415, 499).forEach { assertEquals(PlaybackFailure.NotFound, classify(it), "$it") }
    }

    @Test
    fun oStatusDeErroTemPrioridadeSobreOTipo() {
        assertEquals(PlaybackFailure.NotFound, classify(404, PlaybackErrorKind.Decoding))
        assertEquals(PlaybackFailure.Unavailable, classify(502, PlaybackErrorKind.Io))
        assertEquals(PlaybackFailure.RateLimited, classify(429, PlaybackErrorKind.Decoding))
    }

    @Test
    fun semStatusOTipoDecide() {
        assertEquals(PlaybackFailure.Network, classify(null, PlaybackErrorKind.Io))
        assertEquals(PlaybackFailure.Unsupported, classify(null, PlaybackErrorKind.Decoding))
    }

    @Test
    fun statusQueNaoEErroDeixaOTipoDecidir() {
        listOf(0, 200, 206, 302, 399, 600).forEach {
            assertEquals(PlaybackFailure.Network, classify(it, PlaybackErrorKind.Io), "$it")
            assertEquals(PlaybackFailure.Unsupported, classify(it, PlaybackErrorKind.Decoding), "$it")
        }
    }

    // retryDelay

    @Test
    fun redeERepetidaDuasVezesComEsperaCrescente() {
        assertEquals(1_000L, retryDelay(PlaybackFailure.Network, 0))
        assertEquals(3_000L, retryDelay(PlaybackFailure.Network, 1))
        assertNull(retryDelay(PlaybackFailure.Network, 2))
        assertNull(retryDelay(PlaybackFailure.Network, 10))
    }

    @Test
    fun indisponivelETambemRepetidoDuasVezes() {
        assertEquals(1_000L, retryDelay(PlaybackFailure.Unavailable, 0))
        assertEquals(3_000L, retryDelay(PlaybackFailure.Unavailable, 1))
        assertNull(retryDelay(PlaybackFailure.Unavailable, 2))
    }

    @Test
    fun tentativaNegativaNaoRepete() {
        assertNull(retryDelay(PlaybackFailure.Network, -1))
        assertNull(retryDelay(PlaybackFailure.Unavailable, Int.MIN_VALUE))
    }

    @Test
    fun osDemaisMotivosNuncaRepetem() {
        listOf(
            PlaybackFailure.SessionEnded,
            PlaybackFailure.Forbidden,
            PlaybackFailure.NotFound,
            PlaybackFailure.Gone,
            PlaybackFailure.RateLimited,
            PlaybackFailure.Unsupported,
        ).forEach { failure ->
            (0..3).forEach { assertNull(retryDelay(failure, it), "$failure/$it") }
        }
    }

    @Test
    fun nenhumStatus4xxNemOErroDeFormatoRepete() {
        (400..499).forEach { status ->
            (0..2).forEach { attempt -> assertNull(retryDelay(classify(status), attempt), "$status/$attempt") }
        }
        (0..2).forEach { assertNull(retryDelay(classify(null, PlaybackErrorKind.Decoding), it)) }
    }

    @Test
    fun statusDeServidorEFalhaDeRedeRepetemNoMaximoDuasVezes() {
        val retriable = (500..599).map { classify(it) } + classify(null, PlaybackErrorKind.Io)
        retriable.forEach { failure ->
            val delays = generateSequence(0) { it + 1 }.map { retryDelay(failure, it) }.takeWhile { it != null }.toList()
            assertEquals(listOf<Long?>(1_000L, 3_000L), delays, "$failure")
        }
    }
}
