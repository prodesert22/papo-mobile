package chat.papo.mobile

import chat.papo.mobile.data.api.ApiException
import chat.papo.mobile.data.api.EmbedAuthorDto
import chat.papo.mobile.data.api.EmbedDto
import chat.papo.mobile.data.api.EmbedMediaDto
import chat.papo.mobile.media.embedAuthorKey
import chat.papo.mobile.media.embedImageKey
import chat.papo.mobile.media.fetchEmbedAuthorIcon
import chat.papo.mobile.media.fetchEmbedImage
import chat.papo.mobile.media.hasAuthorIcon
import chat.papo.mobile.media.hasImage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A imagem do embed e o ícone do autor vêm do mesmo `GET /embeds/{id}`: um pedido só, e os dois no cache cifrado. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmbedMediaTest {
    private val requests = CopyOnWriteArrayList<String>()

    // "AQID" são os bytes 1, 2, 3 (a imagem) e "BAUG", os bytes 4, 5, 6 (o ícone), em base64 como o servidor os manda.
    private val image = byteArrayOf(1, 2, 3)
    private val icon = byteArrayOf(4, 5, 6)

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        when (path) {
            "/embeds/e1" -> json("""{"id":"e1","image_data":"AQID","author_image_data":"BAUG"}""")
            "/embeds/so-icone" -> json("""{"id":"so-icone","image_data":null,"author_image_data":"BAUG"}""")
            "/embeds/sem-icone" -> json("""{"id":"sem-icone","image_data":"AQID","author_image_data":null}""")
            "/embeds/sumiu" -> json("""{"type":"x","title":"Recurso não encontrado","status":404}""", HttpStatusCode.NotFound)
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private fun embed(id: String, withImage: Boolean) = EmbedDto(
        id = id,
        thumbnail = if (withImage) EmbedMediaDto(mimeType = "image/png", width = 120, height = 63) else null,
        author = EmbedAuthorDto(name = "Robô de Build", media = EmbedMediaDto(mimeType = "image/png", width = 64, height = 64)),
    )

    @Test
    fun oEmbedTemImagemComThumbnailOuImagemESemNenhumaDasDuasNao() {
        val png = EmbedMediaDto(mimeType = "image/png")
        assertTrue(EmbedDto(id = "e", thumbnail = png).hasImage())
        assertTrue(EmbedDto(id = "e", image = png).hasImage())
        assertTrue(EmbedDto(id = "e", thumbnail = png, image = png).hasImage())
        // O vídeo e o ícone do autor não são a imagem que o servidor serve como `image_data`.
        assertFalse(EmbedDto(id = "e", video = png, author = EmbedAuthorDto(media = png)).hasImage())
    }

    @Test
    fun oIconeDoAutorSoExisteQuandoOAutorTemMidia() {
        val png = EmbedMediaDto(mimeType = "image/png")
        assertTrue(EmbedDto(id = "e", author = EmbedAuthorDto(name = "Robô de Build", media = png)).hasAuthorIcon())
        assertFalse(EmbedDto(id = "e", author = EmbedAuthorDto(name = "Robô de Build")).hasAuthorIcon())
        assertFalse(EmbedDto(id = "e", thumbnail = png).hasAuthorIcon())
    }

    @Test
    fun umPedidoTrazAImagemEGravaOIconeNoCache() = runTest {
        val session = TestResources.session(engine, this)

        assertContentEquals(image, session.fetchEmbedImage("e1"))

        assertContentEquals(icon, session.media.peek(embedAuthorKey("e1")))
        assertEquals(listOf("GET /embeds/e1"), requests.toList())
    }

    @Test
    fun semImagemNoServidorADevolucaoEnullMasOIconeFicaGuardado() = runTest {
        val session = TestResources.session(engine, this)

        assertNull(session.fetchEmbedImage("so-icone"))

        assertContentEquals(icon, session.media.peek(embedAuthorKey("so-icone")))
    }

    @Test
    fun semIconeNoServidorSoAImagemVaiParaOCache() = runTest {
        val session = TestResources.session(engine, this)

        assertContentEquals(image, session.fetchEmbedImage("sem-icone"))

        assertNull(session.media.peek(embedAuthorKey("sem-icone")))
    }

    @Test
    fun imagemEIconePedidosJuntosFazemUmPedidoSo() = runTest {
        val session = TestResources.session(engine, this)
        val e1 = embed("e1", withImage = true)

        // Como o cartão: a imagem e o ícone são pedidos na mesma composição, e o cache junta os dois pedidos num.
        val (shown, avatar) = listOf(
            async { session.media.get(embedImageKey("e1")) { session.fetchEmbedImage("e1") } },
            async { session.media.get(embedAuthorKey("e1")) { session.fetchEmbedAuthorIcon(e1) } },
        ).awaitAll()

        assertContentEquals(image, shown)
        assertContentEquals(icon, avatar)
        assertEquals(listOf("GET /embeds/e1"), requests.toList())
    }

    @Test
    fun oIconePedidoAntesDaImagemTambemFazUmPedidoSo() = runTest {
        val session = TestResources.session(engine, this)
        val e1 = embed("e1", withImage = true)

        val avatar = session.media.get(embedAuthorKey("e1")) { session.fetchEmbedAuthorIcon(e1) }
        val shown = session.media.get(embedImageKey("e1")) { session.fetchEmbedImage("e1") }

        assertContentEquals(icon, avatar)
        assertContentEquals(image, shown)
        assertEquals(listOf("GET /embeds/e1"), requests.toList())
    }

    @Test
    fun semImagemOIconeEARazaoDoPedido() = runTest {
        val session = TestResources.session(engine, this)

        assertContentEquals(icon, session.fetchEmbedAuthorIcon(embed("so-icone", withImage = false)))

        assertEquals(listOf("GET /embeds/so-icone"), requests.toList())
    }

    @Test
    fun servidorSemOIconeDevolveNull() = runTest {
        val session = TestResources.session(engine, this)

        assertNull(session.fetchEmbedAuthorIcon(embed("sem-icone", withImage = true)))
        assertNull(session.fetchEmbedAuthorIcon(embed("sem-icone", withImage = false)))
    }

    @Test
    fun imagemJaNoCacheSemOIconeNaoPedeNadaPorCausaDele() = runTest {
        val session = TestResources.session(engine, this)
        // O cache de uma versão que ainda não guardava o ícone: a imagem está lá, o ícone não.
        session.media.put(embedImageKey("e1"), image)

        assertNull(session.fetchEmbedAuthorIcon(embed("e1", withImage = true)))

        assertEquals(emptyList(), requests.toList())
    }

    @Test
    fun erroDoServidorSobeParaQuemPediu() = runTest {
        val session = TestResources.session(engine, this)

        assertEquals(404, assertFailsWith<ApiException> { session.fetchEmbedImage("sumiu") }.status)
        assertEquals(404, assertFailsWith<ApiException> { session.fetchEmbedAuthorIcon(embed("sumiu", withImage = false)) }.status)
        assertEquals(404, assertFailsWith<ApiException> { session.fetchEmbedAuthorIcon(embed("sumiu", withImage = true)) }.status)
    }
}
