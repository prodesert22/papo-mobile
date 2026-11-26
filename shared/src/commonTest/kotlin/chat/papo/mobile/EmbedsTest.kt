package chat.papo.mobile

import chat.papo.mobile.account.ServerUrl
import chat.papo.mobile.chat.EmbedImageFile
import chat.papo.mobile.chat.embedImageFile
import chat.papo.mobile.chat.embedMediaAspect
import chat.papo.mobile.chat.fieldRows
import chat.papo.mobile.chat.hasViewableImage
import chat.papo.mobile.chat.isGifEmbed
import chat.papo.mobile.chat.safeLink
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedFieldDto
import chat.papo.mobile.data.api.EmbedMediaDto
import chat.papo.mobile.data.api.EmbedWithImageDto
import chat.papo.mobile.data.api.PapoJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbedsTest {
    private fun field(name: String, position: Int, inline: Boolean = false, value: String = "valor $name") =
        EmbedFieldDto(position = position, name = name, value = value, inline = inline)

    private fun names(rows: List<List<EmbedFieldDto>>) = rows.map { row -> row.map { it.name } }

    private fun media(width: Int?, height: Int?) = EmbedMediaDto(mimeType = "image/png", width = width, height = height)

    private fun embed(
        thumbnail: EmbedMediaDto? = null,
        image: EmbedMediaDto? = null,
        video: EmbedMediaDto? = null,
        fetchMethod: String? = null,
    ) = EmbedDto(id = "e1", fetchMethod = fetchMethod, thumbnail = thumbnail, image = image, video = video)

    // Decodificação

    @Test
    fun embedCustomizadoCompletoDecodificaTodosOsCampos() {
        val embed = PapoJson.decodeFromString<EmbedDto>(
            """{"id":"e1","source_type":"custom","fetch_method":"manual","site_name":"Painel da Casa",
                "url":"https://painel.example.com/build/42","title":"Build 42 passou","description":"Tudo **verde** em 3 min.",
                "color":"#2ECC71",
                "author":{"name":"Robô de Build","url":"https://painel.example.com/robo","media":{"mime_type":"image/png","width":64,"height":64}},
                "thumbnail":{"mime_type":"image/png","width":1200,"height":630,"size_bytes":71770},
                "footer":{"text":"Executado às 10h"},
                "fields":[{"position":1,"name":"Duração","value":"3 min","inline":true},
                          {"position":0,"name":"Ramo","value":"principal","inline":true},
                          {"position":2,"name":"Notas","value":"sem falhas","inline":false}],
                "created_at":"2026-01-01T00:00:00Z","campo_novo":1}""",
        )

        assertEquals("manual", embed.fetchMethod)
        assertEquals("#2ECC71", embed.color)
        assertEquals("Robô de Build", embed.author?.name)
        assertEquals("https://painel.example.com/robo", embed.author?.url)
        assertEquals(64, embed.author?.media?.width)
        assertEquals("Executado às 10h", embed.footer?.text)
        assertEquals(71770L, embed.thumbnail?.sizeBytes)
        assertEquals(listOf("Duração", "Ramo", "Notas"), embed.fields.map { it.name })
        assertEquals(listOf(true, true, false), embed.fields.map { it.inline })
        // A grade desse embed: os dois `inline` por posição (Ramo, Duração) e o campo largo embaixo.
        assertEquals(listOf(listOf("Ramo", "Duração"), listOf("Notas")), names(fieldRows(embed.fields)))
    }

    @Test
    fun embedAntigoSemOsCamposNovosContinuaDecodificando() {
        val embed = PapoJson.decodeFromString<EmbedDto>("""{"id":"e1","title":"Página","url":"https://x.test"}""")
        assertNull(embed.color)
        assertNull(embed.author)
        assertNull(embed.footer)
        assertTrue(embed.fields.isEmpty())
        assertTrue(fieldRows(embed.fields).isEmpty())
        assertNull(embedMediaAspect(embed))
    }

    @Test
    fun respostaDoEmbedTrazAImagemEOIconeDoAutor() {
        val response = PapoJson.decodeFromString<EmbedWithImageDto>("""{"id":"e1","image_data":"AQID","author_image_data":"BAUG"}""")
        assertEquals("AQID", response.imageData)
        assertEquals("BAUG", response.authorImageData)
        assertNull(PapoJson.decodeFromString<EmbedWithImageDto>("""{"image_data":null,"author_image_data":null}""").authorImageData)
    }

    // fieldRows

    @Test
    fun semCamposNaoHaLinhas() {
        assertTrue(fieldRows(emptyList()).isEmpty())
    }

    @Test
    fun camposInlineVaoDeDoisEmDois() {
        val rows = fieldRows(listOf(field("a", 0, true), field("b", 1, true), field("c", 2, true), field("d", 3, true)))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), names(rows))
    }

    @Test
    fun inlineQueSobraFicaSozinhoNoFim() {
        assertEquals(listOf(listOf("a", "b"), listOf("c")), names(fieldRows(listOf(field("a", 0, true), field("b", 1, true), field("c", 2, true)))))
        assertEquals(listOf(listOf("a")), names(fieldRows(listOf(field("a", 0, true)))))
    }

    @Test
    fun campoLargoFechaALinhaInlineAberta() {
        val rows = fieldRows(listOf(field("a", 0, true), field("largo", 1), field("b", 2, true), field("c", 3, true)))
        assertEquals(listOf(listOf("a"), listOf("largo"), listOf("b", "c")), names(rows))
    }

    @Test
    fun camposLargosSeguidosCadaUmNaSuaLinha() {
        assertEquals(listOf(listOf("a"), listOf("b")), names(fieldRows(listOf(field("a", 0), field("b", 1)))))
    }

    @Test
    fun camposSeguemAOrdemDaPosicaoEEmpateMantemAOrdemDeChegada() {
        val rows = fieldRows(listOf(field("terceiro", 2), field("primeiro", 0), field("segundo-a", 1), field("segundo-b", 1)))
        assertEquals(listOf("primeiro", "segundo-a", "segundo-b", "terceiro"), names(rows).flatten())
    }

    @Test
    fun campoSemNomeENemValorNaoDesenhaNada() {
        val rows = fieldRows(listOf(field("a", 0, true), field("", 1, true, value = " "), field("b", 2, true)))
        assertEquals(listOf(listOf("a", "b")), names(rows))
        // Só o nome ou só o valor basta para o campo ficar.
        assertEquals(2, fieldRows(listOf(field("só nome", 0, value = ""), field("", 1, value = "só valor"))).size)
    }

    // safeLink

    @Test
    fun soHttpEHttpsViramLink() {
        assertEquals("https://exemplo.com/p?q=1#topo", safeLink("https://exemplo.com/p?q=1#topo"))
        assertEquals("http://exemplo.com", safeLink("http://exemplo.com"))
        // O esquema sai em minúsculas: o Android não resolve `Https://`.
        assertEquals("https://Exemplo.com/Caminho", safeLink("HTTPS://Exemplo.com/Caminho"))
    }

    @Test
    fun outrosEsquemasEUrlsInvalidasNaoViramLink() {
        listOf(
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "intent://exemplo.com#Intent;scheme=https;end",
            "file:///sdcard/segredo.txt",
            "content://com.exemplo/arquivo",
            "data:text/html,<b>oi</b>",
            "tel:+5511999999999",
            "ftp://exemplo.com/arquivo",
            "exemplo.com/sem-esquema",
            "//exemplo.com",
            "https://",
            "https:///sem-host",
            "https://exemplo.com/com espaço",
            "https://exemplo.com/\nquebra",
            " https://exemplo.com",
            "",
        ).forEach { assertNull(safeLink(it), "[$it]") }
        assertNull(safeLink(null))
    }

    // embedMediaAspect

    @Test
    fun aProporcaoVemDasMedidasDaThumbnail() {
        assertEquals(1200f / 630f, embedMediaAspect(embed(thumbnail = media(1200, 630))))
        assertEquals(1f, embedMediaAspect(embed(thumbnail = media(512, 512))))
    }

    @Test
    fun semThumbnailValeAImagem() {
        assertEquals(1f, embedMediaAspect(embed(image = media(300, 300))))
    }

    @Test
    fun aThumbnailVenceAImagemPorqueEsseECaQueOServidorServe() {
        assertEquals(2f, embedMediaAspect(embed(thumbnail = media(400, 200), image = media(300, 300))))
    }

    @Test
    fun thumbnailSemMedidasNaoEmprestaAsDaImagem() {
        // O servidor serve a thumbnail; a proporção da `image` seria a de outra figura.
        assertNull(embedMediaAspect(embed(thumbnail = media(null, null), image = media(300, 300))))
        assertEquals(16f / 9f, embedMediaAspect(embed(thumbnail = media(0, 0), image = media(300, 300), video = media(1280, 720))))
    }

    @Test
    fun semMedidasDaImagemValemAsDoVideo() {
        assertEquals(16f / 9f, embedMediaAspect(embed(video = media(1280, 720))))
        assertEquals(16f / 9f, embedMediaAspect(embed(thumbnail = media(null, 100), video = media(1280, 720))))
    }

    @Test
    fun aImagemVenceOVideoQuandoTemMedidas() {
        assertEquals(1f, embedMediaAspect(embed(thumbnail = media(200, 200), video = media(1280, 720))))
    }

    @Test
    fun aProporcaoFicaEntreMeioEDoisEMeio() {
        assertEquals(0.5f, embedMediaAspect(embed(thumbnail = media(100, 1000))))
        assertEquals(2.5f, embedMediaAspect(embed(thumbnail = media(1000, 100))))
        // Nos limites exatos e logo dentro deles, o valor passa sem mudar.
        assertEquals(0.5f, embedMediaAspect(embed(thumbnail = media(50, 100))))
        assertEquals(2.5f, embedMediaAspect(embed(thumbnail = media(250, 100))))
        assertEquals(0.6f, embedMediaAspect(embed(thumbnail = media(60, 100))))
    }

    @Test
    fun medidasAusentesZeroOuNegativasNaoDaoProporcao() {
        assertNull(embedMediaAspect(embed()))
        assertNull(embedMediaAspect(embed(thumbnail = media(null, 100))))
        assertNull(embedMediaAspect(embed(thumbnail = media(100, null))))
        assertNull(embedMediaAspect(embed(thumbnail = media(0, 100))))
        assertNull(embedMediaAspect(embed(thumbnail = media(100, 0))))
        assertNull(embedMediaAspect(embed(thumbnail = media(-100, 100))))
        assertNull(embedMediaAspect(embed(thumbnail = media(100, -100))))
    }

    // isGifEmbed

    @Test
    fun soOEmbedDeGifComThumbnailEHandledComoGif() {
        assertTrue(isGifEmbed(embed(thumbnail = media(498, 280), fetchMethod = "gif")))
        assertFalse(isGifEmbed(embed(thumbnail = media(498, 280), fetchMethod = "opengraph")))
        assertFalse(isGifEmbed(embed(thumbnail = media(498, 280))))
        // Sem a thumbnail não há o que animar: segue como cartão comum.
        assertFalse(isGifEmbed(embed(fetchMethod = "gif")))
    }

    // hasViewableImage

    private val server = ServerUrl("https://papo.test")

    private val relayMedia = EmbedMediaDto(url = "https://videos.example.com/a.mp4", mimeType = "video/mp4", width = 1280, height = 720)

    private val youTubeVideo = EmbedMediaDto(url = "https://www.youtube.com/embed/aB3dE5gH7jK")

    private fun viewable(embed: EmbedDto) = hasViewableImage(server, embed)

    @Test
    fun semImagemNaoHaOQueAbrir() {
        assertFalse(viewable(embed()))
        assertFalse(viewable(embed(fetchMethod = "gif")))
        // Um embed só com vídeo também não: a imagem é que decide.
        assertFalse(viewable(embed(video = relayMedia)))
    }

    @Test
    fun aImagemDeUmEmbedComumOuCustomizadoAbre() {
        assertTrue(viewable(embed(thumbnail = media(1200, 630))))
        assertTrue(viewable(embed(image = media(800, 450), fetchMethod = "manual")))
        assertTrue(viewable(embed(thumbnail = media(null, null))))
    }

    @Test
    fun aImagemDoGifAbreMesmoComVideo() {
        assertTrue(viewable(embed(thumbnail = media(320, 180), fetchMethod = "gif")))
        // O cartão de GIF não tem o gesto do vídeo: a imagem abre, qualquer que seja o resto.
        assertTrue(viewable(embed(thumbnail = media(320, 180), fetchMethod = "gif", video = relayMedia)))
        // `gif` sem thumbnail só com `image` é um cartão comum.
        assertTrue(viewable(embed(image = media(320, 180), fetchMethod = "gif")))
    }

    @Test
    fun aImagemDeUmEmbedComVideoDoRelayOuDoYouTubeFicaComOGestoDoVideo() {
        assertFalse(viewable(embed(thumbnail = media(1200, 630), video = relayMedia)))
        assertFalse(viewable(embed(image = media(1200, 630), video = relayMedia)))
        assertFalse(viewable(embed(thumbnail = media(480, 270), video = youTubeVideo, fetchMethod = "oembed")))
        // O campo antigo `embed_url` também diz que é o YouTube.
        assertFalse(viewable(embed(thumbnail = media(480, 270)).copy(embedUrl = "https://www.youtube.com/embed/aB3dE5gH7jK")))
    }

    @Test
    fun videoQueOCartaoNaoTocaDeixaAImagemAbrir() {
        // `http://`, MIME fora do relay e uma página que só parece ser a do YouTube: o cartão mostra só a imagem.
        assertTrue(viewable(embed(thumbnail = media(1200, 630), video = relayMedia.copy(url = "http://videos.example.com/a.mp4"))))
        assertTrue(viewable(embed(thumbnail = media(1200, 630), video = relayMedia.copy(mimeType = "video/x-msvideo"))))
        assertTrue(viewable(embed(thumbnail = media(480, 270), video = EmbedMediaDto(url = "https://youtube.com.evil.example.com/embed/aB3dE5gH7jK"))))
    }

    // embedImageFile

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val gif = "GIF89a".encodeToByteArray() + ByteArray(10)

    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0) + ByteArray(10)

    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(20)

    private val webp = "RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray() + "VP8 ".encodeToByteArray() + ByteArray(10)

    /** Um WebP estendido (`VP8X`) com o bit de animação ligado. */
    private val animatedWebp = "RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray() + "VP8X".encodeToByteArray() +
        bytes(10, 0, 0, 0, 0x02, 0, 0, 0) + ByteArray(6)

    private val heif = ByteArray(4) + "ftypheic".encodeToByteArray() + ByteArray(10)

    private fun titled(title: String?, site: String? = null, provider: String? = null, mime: String? = "image/png") =
        EmbedDto(id = "e1", title = title, siteName = site, provider = provider, thumbnail = EmbedMediaDto(mimeType = mime))

    @Test
    fun oNomeSaiDoTituloComAExtensaoDosBytes() {
        val embed = titled("Build 42 passou")
        assertEquals(EmbedImageFile("Build 42 passou.gif", "image/gif"), embedImageFile(embed, gif))
        assertEquals(EmbedImageFile("Build 42 passou.jpg", "image/jpeg"), embedImageFile(embed, jpeg))
        assertEquals(EmbedImageFile("Build 42 passou.png", "image/png"), embedImageFile(embed, png))
        assertEquals(EmbedImageFile("Build 42 passou.webp", "image/webp"), embedImageFile(embed, webp))
        assertEquals(EmbedImageFile("Build 42 passou.webp", "image/webp"), embedImageFile(embed, animatedWebp))
        assertEquals(EmbedImageFile("Build 42 passou.heic", "image/heif"), embedImageFile(embed, heif))
    }

    @Test
    fun umPngAnimadoSaiComoPng() {
        val chunk = { type: String, size: Int -> bytes(0, 0, 0, size) + type.encodeToByteArray() + ByteArray(size) + ByteArray(4) }
        val apng = png.copyOf(8) + chunk("IHDR", 13) + chunk("acTL", 8) + chunk("IDAT", 4)
        assertEquals(EmbedImageFile("Quadro.png", "image/png"), embedImageFile(titled("Quadro"), apng))
    }

    @Test
    fun osBytesVencemOMimeTypeQueOServidorInformou() {
        // O servidor disse GIF, os bytes são de um JPEG: vale o que está no arquivo.
        assertEquals(EmbedImageFile("Foto.jpg", "image/jpeg"), embedImageFile(titled("Foto", mime = "image/gif"), jpeg))
    }

    @Test
    fun comBytesQueNaoSaoReconhecidosValeOMimeTypeDaImagemQueOServidorServe() {
        val unknown = ByteArray(16) { 7 }
        assertEquals("Foto.png", embedImageFile(titled("Foto", mime = "image/png"), unknown).fileName)
        assertEquals("image/png", embedImageFile(titled("Foto", mime = "IMAGE/PNG; charset=binary"), unknown).mimeType)
        // O tipo da `thumbnail` é o do arquivo servido; sem ela, o da `image`.
        val onlyImage = EmbedDto(id = "e1", title = "Foto", image = EmbedMediaDto(mimeType = "image/webp"))
        assertEquals("Foto.webp", embedImageFile(onlyImage, unknown).fileName)
        val both = EmbedDto(id = "e1", title = "Foto", thumbnail = EmbedMediaDto(mimeType = "image/png"), image = EmbedMediaDto(mimeType = "image/webp"))
        assertEquals("Foto.png", embedImageFile(both, unknown).fileName)
        // Um tipo de imagem sem extensão conhecida mantém o tipo e o nome sem extensão.
        assertEquals(EmbedImageFile("Foto", "image/avif"), embedImageFile(titled("Foto", mime = "image/avif"), unknown))
    }

    @Test
    fun semFormatoNemTipoDeImagemSaiComoBinarioGenerico() {
        val unknown = ByteArray(16) { 7 }
        for (mime in listOf(null, "", "text/html", "video/mp4", "image/")) {
            assertEquals(EmbedImageFile("Foto", "application/octet-stream"), embedImageFile(titled("Foto", mime = mime), unknown), "mime=$mime")
        }
        // Sem nenhuma mídia no embed o resultado é o mesmo.
        assertEquals("Foto", embedImageFile(EmbedDto(id = "e1", title = "Foto"), unknown).fileName)
    }

    @Test
    fun semTituloOSiteEOProvedorDaoONomeEDepoisImagem() {
        assertEquals("exemplo.com.png", embedImageFile(titled(null, site = "exemplo.com", provider = "Outro"), png).fileName)
        assertEquals("exemplo.com.png", embedImageFile(titled("   ", site = "exemplo.com"), png).fileName)
        assertEquals("Tenor.gif", embedImageFile(titled(null, provider = "Tenor"), gif).fileName)
        assertEquals("imagem.gif", embedImageFile(titled(null), gif).fileName)
        assertEquals("imagem.gif", embedImageFile(titled("///", site = "..."), gif).fileName)
    }

    @Test
    fun oNomeNuncaLevaPastaCaractereReservadoControleNemPontosDasPontas() {
        assertEquals("AC DC ao vivo.png", embedImageFile(titled("AC/DC: ao vivo?"), png).fileName)
        assertEquals("a b.png", embedImageFile(titled("..\\../a\n\tb\u0000"), png).fileName)
        assertEquals("um dois tres.png", embedImageFile(titled("  um   dois\u00A0tres  "), png).fileName)
        assertEquals("saida.png", embedImageFile(titled("<saida>|\""), png).fileName)
    }

    @Test
    fun umTituloLongoEhCortadoSemPerderAExtensao() {
        val file = embedImageFile(titled("t".repeat(256)), jpeg)
        assertEquals(128, file.fileName.length)
        assertTrue(file.fileName.endsWith(".jpg"))
    }
}
