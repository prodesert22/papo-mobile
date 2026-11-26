package chat.papo.mobile

import chat.papo.mobile.chat.AttachmentProblem
import chat.papo.mobile.chat.MAX_ATTACHMENTS
import chat.papo.mobile.chat.MAX_ATTACHMENTS_TOTAL_BYTES
import chat.papo.mobile.chat.MAX_ATTACHMENT_BYTES
import chat.papo.mobile.chat.MAX_ATTACHMENT_NAME
import chat.papo.mobile.chat.checkLimits
import chat.papo.mobile.chat.isGif
import chat.papo.mobile.chat.isImage
import chat.papo.mobile.chat.isVisualMedia
import chat.papo.mobile.chat.nameFor
import chat.papo.mobile.chat.receivedMediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NameForTest {
    @Test
    fun semNomeUsaUmGenericoDoTipoComASuaExtensao() {
        assertEquals("imagem.gif", nameFor(null, "image/gif"))
        assertEquals("imagem.jpg", nameFor("", "image/jpeg"))
        assertEquals("video.mp4", nameFor("   ", "video/mp4"))
        assertEquals("audio.ogg", nameFor(null, "audio/ogg"))
        assertEquals("imagem.webp", nameFor(null, "IMAGE/WEBP"))
        assertEquals("imagem.gif", nameFor(null, "image/gif; charset=binary"))
    }

    @Test
    fun tipoSemExtensaoConhecidaFicaSoComONomeGenerico() {
        assertEquals("imagem", nameFor(null, "image/x-desconhecido"))
        assertEquals("video", nameFor(null, "video/x-desconhecido"))
        assertEquals("arquivo", nameFor(null, "application/pdf"))
        assertEquals("arquivo", nameFor(null, "application/octet-stream"))
        assertEquals("arquivo", nameFor(null, ""))
    }

    @Test
    fun nomeDoProvedorFicaComoEstaComAExtensao() {
        assertEquals("IMG_2031.jpg", nameFor("IMG_2031.jpg", "image/jpeg"))
        assertEquals("relatório final.pdf", nameFor("relatório final.pdf", "application/pdf"))
        assertEquals("sem-extensao", nameFor("sem-extensao", "image/png")) // o app não inventa extensão no nome de quem já tem um
    }

    @Test
    fun limpaONomeComoOServidorLimpa() {
        assertEquals("c.png", nameFor("a/b/c.png", "image/png"))
        assertEquals("x.png", nameFor("C:\\fotos\\x.png", "image/png"))
        assertEquals("x.png", nameFor("..\\a/x.png", "image/png"))
        assertEquals("ab.txt", nameFor("a\u0000b\u0007.txt", "text/plain"))
        assertEquals("nota.txt", nameFor("  nota.txt \n", "text/plain"))
        // Só pasta, ou só controle: sobra nada, e vale o genérico.
        assertEquals("imagem.png", nameFor("pasta/", "image/png"))
        assertEquals("arquivo", nameFor("\u0001\u0002", "application/zip"))
    }

    @Test
    fun nomeComAteCentoEVinteEOitoCaracteresNaoMuda() {
        val exact = "a".repeat(MAX_ATTACHMENT_NAME - 4) + ".jpg"
        assertEquals(MAX_ATTACHMENT_NAME, exact.length)
        assertEquals(exact, nameFor(exact, "image/jpeg"))
    }

    @Test
    fun nomeMaisLongoCortaOCorpoEMantemAExtensao() {
        val long = "a".repeat(MAX_ATTACHMENT_NAME + 50) + ".jpg"
        val cut = nameFor(long, "image/jpeg")
        assertEquals(MAX_ATTACHMENT_NAME, cut.length)
        assertTrue(cut.endsWith(".jpg"))
        assertEquals("a".repeat(MAX_ATTACHMENT_NAME - 4) + ".jpg", cut)
    }

    @Test
    fun cemEVinteEOitoCaracteresComExtensaoCabemECentoEVinteENoveSaoCortados() {
        val limit = "b".repeat(MAX_ATTACHMENT_NAME - 3) + ".gz"
        assertEquals(MAX_ATTACHMENT_NAME, limit.length)
        assertEquals(limit, nameFor(limit, "application/gzip"))
        val over = "b".repeat(MAX_ATTACHMENT_NAME - 2) + ".gz"
        assertEquals(limit, nameFor(over, "application/gzip"))
    }

    @Test
    fun nomeLongoSemExtensaoEPontoNoComecoSaoCortadosInteiros() {
        assertEquals("c".repeat(MAX_ATTACHMENT_NAME), nameFor("c".repeat(300), "application/octet-stream"))
        // O ponto no começo não é extensão: é o nome de um arquivo oculto.
        assertEquals("." + "d".repeat(MAX_ATTACHMENT_NAME - 1), nameFor("." + "d".repeat(300), "application/octet-stream"))
    }

    @Test
    fun extensaoMuitoCompridaNaoEPoupadaPoisEParteDoNome() {
        val name = "x." + "e".repeat(200)
        assertEquals(name.take(MAX_ATTACHMENT_NAME), nameFor(name, "application/octet-stream"))
    }

    @Test
    fun cortarNaoDeixaUmEspacoAntesDaExtensao() {
        // O corte do corpo cai logo depois do espaço; o servidor apara as pontas, e um espaço antes do ponto ficaria no nome.
        val name = "f".repeat(MAX_ATTACHMENT_NAME - 5) + " " + "g".repeat(30) + ".png"
        assertEquals("f".repeat(MAX_ATTACHMENT_NAME - 5) + ".png", nameFor(name, "image/png"))
    }

    @Test
    fun contaPontosDeCodigoENaoUnidadesDeTexto() {
        // Cada emoji ocupa dois `Char`, mas é um caractere para o servidor: 128 deles cabem.
        val emojis = "😀".repeat(MAX_ATTACHMENT_NAME)
        assertEquals(emojis, nameFor(emojis, "image/png"))
        // Com o 129º e uma extensão, o corte deixa 124 emojis, inteiros, e a extensão: 128 caracteres.
        assertEquals("😀".repeat(MAX_ATTACHMENT_NAME - 4) + ".png", nameFor(emojis + "😀.png", "image/png"))
    }

    @Test
    fun cortarNuncaPartePorMeioUmParSubstituto() {
        assertEquals("😀".repeat(MAX_ATTACHMENT_NAME), nameFor("😀".repeat(200), "application/octet-stream"))
    }
}

class CheckLimitsTest {
    private val mib = 1024L * 1024

    @Test
    fun semArquivosNaoHaProblema() {
        assertNull(checkLimits(emptyList()))
    }

    @Test
    fun ateDezArquivosPequenosPassam() {
        assertNull(checkLimits(List(MAX_ATTACHMENTS) { sizedFile("f$it", 1000) }))
    }

    @Test
    fun oDecimoPrimeiroArquivoPassaDoLimite() {
        assertEquals(AttachmentProblem.TooMany, checkLimits(List(MAX_ATTACHMENTS + 1) { sizedFile("f$it", 1000) }))
    }

    @Test
    fun umArquivoDeCemMegabytesPassaEUmByteAMaisNao() {
        assertNull(checkLimits(listOf(sizedFile("exato.bin", MAX_ATTACHMENT_BYTES))))
        assertEquals(AttachmentProblem.FileTooLarge("grande.bin"), checkLimits(listOf(sizedFile("grande.bin", MAX_ATTACHMENT_BYTES + 1))))
    }

    @Test
    fun aSomaDeCentoECincoMegabytesPassaEUmByteAMaisNao() {
        val hundred = sizedFile("a.bin", MAX_ATTACHMENT_BYTES)
        assertEquals(105 * mib, MAX_ATTACHMENTS_TOTAL_BYTES)
        assertNull(checkLimits(listOf(hundred, sizedFile("b.bin", MAX_ATTACHMENTS_TOTAL_BYTES - MAX_ATTACHMENT_BYTES))))
        assertEquals(
            AttachmentProblem.TotalTooLarge,
            checkLimits(listOf(hundred, sizedFile("b.bin", MAX_ATTACHMENTS_TOTAL_BYTES - MAX_ATTACHMENT_BYTES + 1))),
        )
    }

    @Test
    fun arquivoSemTamanhoNaoPesaNaConta() {
        assertNull(checkLimits(listOf(sizedFile("a.bin", null), sizedFile("b.bin", MAX_ATTACHMENT_BYTES), sizedFile("c.bin", 5 * mib))))
    }

    @Test
    fun cadaLimiteDecideSozinhoEOPrimeiroQueFalhaEOInformado() {
        // Onze arquivos, um deles grande demais: a quantidade vem primeiro.
        val many = List(MAX_ATTACHMENTS) { sizedFile("f$it", 10) } + sizedFile("enorme.bin", MAX_ATTACHMENT_BYTES + 1)
        assertEquals(AttachmentProblem.TooMany, checkLimits(many))
        // Um arquivo grande demais que também estoura a soma: o do arquivo vem antes.
        assertEquals(AttachmentProblem.FileTooLarge("enorme.bin"), checkLimits(listOf(sizedFile("enorme.bin", 200 * mib), sizedFile("b.bin", 10 * mib))))
        // O nome informado é o do primeiro arquivo que passa.
        assertEquals(
            AttachmentProblem.FileTooLarge("um.bin"),
            checkLimits(listOf(sizedFile("ok.bin", 1), sizedFile("um.bin", 101 * mib), sizedFile("dois.bin", 102 * mib))),
        )
    }
}

class FileKindTest {
    @Test
    fun soImageGifELeitoComoGifSemImportarMaiusculasNemParametros() {
        assertTrue(sizedFile("a", 1, "image/gif").isGif())
        assertTrue(sizedFile("a", 1, "IMAGE/GIF; x=y").isGif())
        assertFalse(sizedFile("a", 1, "image/png").isGif())
        assertFalse(sizedFile("a", 1, "application/octet-stream").isGif())
    }

    @Test
    fun soImagemAbreInteiraNaPreviaEOGifTambemELida() {
        assertTrue(sizedFile("a", 1, "image/jpeg").isImage())
        assertTrue(sizedFile("a", 1, "IMAGE/GIF; x=y").isImage())
        assertFalse(sizedFile("a", 1, "video/mp4").isImage())
        assertFalse(sizedFile("a", 1, "application/octet-stream").isImage())
    }

    @Test
    fun imagemEVideoMostramPreviaOResto_naoMostra() {
        assertTrue(sizedFile("a", 1, "image/jpeg").isVisualMedia())
        assertTrue(sizedFile("a", 1, "Video/MP4").isVisualMedia())
        assertFalse(sizedFile("a", 1, "audio/ogg").isVisualMedia())
        assertFalse(sizedFile("a", 1, "application/pdf").isVisualMedia())
    }
}

/** O tipo do que o teclado entrega ou o que se cola: só imagem e vídeo viram anexo, o resto fica para o campo de texto. */
class ReceivedMediaTypeTest {
    @Test
    fun valeOTipoQueOProvedorDizQuandoEImagemOuVideo() {
        assertEquals("image/gif", receivedMediaType("image/gif", listOf("image/png")))
        assertEquals("video/mp4", receivedMediaType("VIDEO/MP4; codecs=avc1", emptyList()))
    }

    @Test
    fun semTipoDoProvedorValeOQueOTecladoDeclarouQuandoESoUm() {
        assertEquals("image/webp", receivedMediaType(null, listOf("image/webp")))
        assertEquals("image/png", receivedMediaType("application/octet-stream", listOf("image/png", "IMAGE/PNG")))
        assertEquals("image/gif", receivedMediaType("", listOf("image/gif")))
    }

    @Test
    fun declaracaoComMaisDeUmTipoNaoDecide() {
        assertNull(receivedMediaType(null, listOf("image/gif", "image/png")))
        assertNull(receivedMediaType(null, emptyList()))
    }

    @Test
    fun oProvedorTemAPalavraSobreADeclaracaoDoTeclado() {
        assertNull(receivedMediaType("text/plain", listOf("image/gif")))
        assertNull(receivedMediaType("application/pdf", listOf("image/gif")))
    }

    @Test
    fun tipoGenericoOuQueNaoEMidiaNaoVira() {
        assertNull(receivedMediaType("image/*", emptyList()))
        assertNull(receivedMediaType(null, listOf("video/*")))
        assertNull(receivedMediaType(null, listOf("*/*")))
        assertNull(receivedMediaType(null, listOf("image/")))
        assertNull(receivedMediaType("audio/ogg", emptyList()))
        assertNull(receivedMediaType("text/plain", emptyList()))
    }
}
