package chat.papo.mobile

import chat.papo.mobile.chat.embedMediaAspect
import chat.papo.mobile.chat.fieldRows
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
}
